/*
 * IdeaVim - Vim emulator for IDEs based on the IntelliJ platform
 * Copyright (C) 2003-2022 The IdeaVim authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.ex.ExException
import com.maddyhome.idea.vim.ex.ranges.Ranges
import com.maddyhome.idea.vim.newapi.IjVimEditor
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult
import com.maddyhome.idea.vim.vimscript.model.Script
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimBlob
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDictionary
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimFuncref
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimList
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.expressions.EnvVariableExpression
import com.maddyhome.idea.vim.vimscript.model.expressions.Expression
import com.maddyhome.idea.vim.vimscript.model.expressions.OneElementSublistExpression
import com.maddyhome.idea.vim.vimscript.model.expressions.OptionExpression
import com.maddyhome.idea.vim.vimscript.model.expressions.Register
import com.maddyhome.idea.vim.vimscript.model.expressions.Scope
import com.maddyhome.idea.vim.vimscript.model.expressions.SublistExpression
import com.maddyhome.idea.vim.vimscript.model.expressions.Variable
import com.maddyhome.idea.vim.vimscript.model.expressions.operators.AssignmentOperator
import com.maddyhome.idea.vim.vimscript.model.functions.DefinedFunctionHandler
import com.maddyhome.idea.vim.vimscript.model.statements.FunctionDeclaration
import com.maddyhome.idea.vim.vimscript.model.statements.FunctionFlag
import com.maddyhome.idea.vim.vimscript.services.OptionService

/**
 * see "h :let"
 */
data class LetCommand(
  val ranges: Ranges,
  val variable: Expression,
  val operator: AssignmentOperator,
  val expression: Expression,
  val isSyntaxSupported: Boolean,
) : Command.SingleExecution(ranges) {

  override val argFlags = flags(RangeFlag.RANGE_FORBIDDEN, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)

  @Throws(ExException::class)
  override fun processCommand(editor: Editor, context: DataContext): ExecutionResult {
    if (!isSyntaxSupported) return ExecutionResult.Error
    when (variable) {
      is Variable -> {
        if ((variable.scope == Scope.SCRIPT_VARIABLE && vimContext.getFirstParentContext() !is Script) ||
          (!isInsideFunction(vimContext) && (variable.scope == Scope.FUNCTION_VARIABLE || variable.scope == Scope.LOCAL_VARIABLE))
        ) {
          throw ExException("E461: Illegal variable name: ${variable.toString(editor, context, vimContext)}")
        }

        if (isReadOnlyVariable(variable, editor, context)) {
          throw ExException("E46: Cannot change read-only variable \"${variable.toString(editor, context, vimContext)}\"")
        }

        val leftValue = VimPlugin.getVariableService().getNullableVariableValue(variable, editor, context, vimContext)
        if (leftValue?.isLocked == true && leftValue.lockOwner?.name == variable.name) {
          throw ExException("E741: Value is locked: ${variable.toString(editor, context, vimContext)}")
        }
        val rightValue = expression.evaluate(editor, context, vimContext)
        VimPlugin.getVariableService().storeVariable(variable, operator.getNewValue(leftValue, rightValue), editor, context, this)
      }

      is OneElementSublistExpression -> {
        when (val containerValue = variable.expression.evaluate(editor, context, vimContext)) {
          is VimDictionary -> {
            val dictKey = VimString(variable.index.evaluate(editor, context, this).asString())
            if (operator != AssignmentOperator.ASSIGNMENT && !containerValue.dictionary.containsKey(dictKey)) {
              throw ExException("E716: Key not present in Dictionary: $dictKey")
            }
            val expressionValue = expression.evaluate(editor, context, this)
            var valueToStore = if (dictKey in containerValue.dictionary) {
              if (containerValue.dictionary[dictKey]!!.isLocked) {
                // todo better exception message
                throw ExException("E741: Value is locked: ${variable.originalString}")
              }
              operator.getNewValue(containerValue.dictionary[dictKey]!!, expressionValue)
            } else {
              if (containerValue.isLocked) {
                // todo better exception message
                throw ExException("E741: Value is locked: ${variable.originalString}")
              }
              expressionValue
            }
            if (valueToStore is VimFuncref && !valueToStore.isSelfFixed &&
              valueToStore.handler is DefinedFunctionHandler &&
              (valueToStore.handler as DefinedFunctionHandler).function.flags.contains(FunctionFlag.DICT)
            ) {
              valueToStore = valueToStore.copy()
              valueToStore.dictionary = containerValue
            }
            containerValue.dictionary[dictKey] = valueToStore
          }
          is VimList -> {
            // we use Integer.parseInt(........asString()) because in case if index's type is Float, List, Dictionary etc
            // vim throws the same error as the asString() method
            val index = Integer.parseInt(variable.index.evaluate(editor, context, this).asString())
            if (index > containerValue.values.size - 1) {
              throw ExException("E684: list index out of range: $index")
            }
            if (containerValue.values[index].isLocked) {
              throw ExException("E741: Value is locked: ${variable.originalString}")
            }
            containerValue.values[index] = operator.getNewValue(containerValue.values[index], expression.evaluate(editor, context, vimContext))
          }
          is VimBlob -> TODO()
          else -> throw ExException("E689: Can only index a List, Dictionary or Blob")
        }
      }

      is SublistExpression -> {
        if (variable.expression is Variable) {
          val variableValue = VimPlugin.getVariableService().getNonNullVariableValue(variable.expression, editor, context, this)
          if (variableValue is VimList) {
            // we use Integer.parseInt(........asString()) because in case if index's type is Float, List, Dictionary etc
            // vim throws the same error as the asString() method
            val from = Integer.parseInt(variable.from?.evaluate(editor, context, this)?.toString() ?: "0")
            val to = Integer.parseInt(
              variable.to?.evaluate(editor, context, this)?.toString()
                ?: (variableValue.values.size - 1).toString()
            )

            val expressionValue = expression.evaluate(editor, context, this)
            if (expressionValue !is VimList && expressionValue !is VimBlob) {
              throw ExException("E709: [:] requires a List or Blob value")
            } else if (expressionValue is VimList) {
              if (expressionValue.values.size < to - from + 1) {
                throw ExException("E711: List value does not have enough items")
              } else if (variable.to != null && expressionValue.values.size > to - from + 1) {
                throw ExException("E710: List value has more items than targets")
              }
              val newListSize = expressionValue.values.size - (to - from + 1) + variableValue.values.size
              var i = from
              if (newListSize > variableValue.values.size) {
                while (i < variableValue.values.size) {
                  variableValue.values[i] = expressionValue.values[i - from]
                  i += 1
                }
                while (i < newListSize) {
                  variableValue.values.add(expressionValue.values[i - from])
                  i += 1
                }
              } else {
                while (i <= to) {
                  variableValue.values[i] = expressionValue.values[i - from]
                  i += 1
                }
              }
            } else if (expressionValue is VimBlob) {
              TODO()
            }
          } else {
            throw ExException("wrong variable type")
          }
        }
      }

      is OptionExpression -> {
        val optionValue = variable.evaluate(editor, context, vimContext)
        if (operator == AssignmentOperator.ASSIGNMENT || operator == AssignmentOperator.CONCATENATION ||
          operator == AssignmentOperator.ADDITION || operator == AssignmentOperator.SUBTRACTION
        ) {
          val newValue = operator.getNewValue(optionValue, expression.evaluate(editor, context, this))
          when (variable.scope) {
            Scope.GLOBAL_VARIABLE -> VimPlugin.getOptionService().setOptionValue(OptionService.Scope.GLOBAL, variable.optionName, newValue, variable.originalString)
            Scope.LOCAL_VARIABLE -> VimPlugin.getOptionService().setOptionValue(OptionService.Scope.LOCAL(IjVimEditor(editor)), variable.optionName, newValue, variable.originalString)
            else -> throw ExException("Invalid option scope")
          }
        } else {
          TODO()
        }
      }

      is EnvVariableExpression -> TODO()

      is Register -> {
        if (!(variable.char.isLetter() || variable.char.isDigit() || variable.char == '"')) {
          throw ExException("Let command supports only 0-9a-zA-Z\" registers at the moment")
        }

        VimPlugin.getRegister().startRecording(editor, variable.char)
        VimPlugin.getRegister().recordText(expression.evaluate(editor, context, vimContext).asString())
        VimPlugin.getRegister().finishRecording(editor)
      }

      else -> throw ExException("E121: Undefined variable")
    }
    return ExecutionResult.Success
  }

  private fun isInsideFunction(vimLContext: VimLContext): Boolean {
    var isInsideFunction = false
    var node = vimLContext
    while (!node.isFirstParentContext()) {
      if (node is FunctionDeclaration) {
        isInsideFunction = true
      }
      node = node.getPreviousParentContext()
    }
    return isInsideFunction
  }

  private fun isReadOnlyVariable(variable: Variable, editor: Editor, context: DataContext): Boolean {
    if (variable.scope == Scope.FUNCTION_VARIABLE) return true
    if (variable.scope == null && variable.name.evaluate(editor, context, vimContext).value == "self" && isInsideDictionaryFunction()) return true
    return false
  }

  private fun isInsideDictionaryFunction(): Boolean {
    var node: VimLContext = this
    while (!node.isFirstParentContext()) {
      if (node is FunctionDeclaration && node.flags.contains(FunctionFlag.DICT)) {
        return true
      }
      node = node.getPreviousParentContext()
    }
    return false
  }
}
