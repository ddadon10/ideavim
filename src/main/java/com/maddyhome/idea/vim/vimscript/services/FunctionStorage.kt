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

package com.maddyhome.idea.vim.vimscript.services

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.maddyhome.idea.vim.ex.ExException
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.Script
import com.maddyhome.idea.vim.vimscript.model.VimLContext
import com.maddyhome.idea.vim.vimscript.model.expressions.Scope
import com.maddyhome.idea.vim.vimscript.model.functions.DefinedFunctionHandler
import com.maddyhome.idea.vim.vimscript.model.functions.FunctionBeanClass
import com.maddyhome.idea.vim.vimscript.model.functions.FunctionHandler
import com.maddyhome.idea.vim.vimscript.model.statements.FunctionDeclaration

object FunctionStorage {

  private val logger = logger<FunctionStorage>()

  private val globalFunctions: MutableMap<String, FunctionDeclaration> = mutableMapOf()

  private val extensionPoint = ExtensionPointName.create<FunctionBeanClass>("IdeaVIM.vimLibraryFunction")
  private val builtInFunctions: MutableMap<String, FunctionHandler> = mutableMapOf()

  fun deleteFunction(name: String, scope: Scope? = null, vimContext: VimLContext) {
    if (name[0].isLowerCase() && scope != Scope.SCRIPT_VARIABLE) {
      throw ExException("E128: Function name must start with a capital or \"s:\": $name")
    }

    if (scope != null)
      when (scope) {
        Scope.GLOBAL_VARIABLE -> {
          if (globalFunctions.containsKey(name)) {
            globalFunctions[name]!!.isDeleted = true
            globalFunctions.remove(name)
            return
          } else {
            throw ExException("E130: Unknown function: ${scope.c}:$name")
          }
        }
        Scope.SCRIPT_VARIABLE -> {
          if (vimContext.getFirstParentContext() !is Script) {
            throw ExException("E81: Using <SID> not in a script context")
          }

          if (getScriptFunction(name, vimContext) != null) {
            deleteScriptFunction(name, vimContext)
            return
          } else {
            throw ExException("E130: Unknown function: ${scope.c}:$name")
          }
        }
        else -> throw ExException("E130: Unknown function: ${scope.c}:$name")
      }

    if (globalFunctions.containsKey(name)) {
      globalFunctions[name]!!.isDeleted = true
      globalFunctions.remove(name)
      return
    }

    val firstParentContext = vimContext.getFirstParentContext()
    if (firstParentContext is Script && getScriptFunction(name, vimContext) != null) {
      deleteScriptFunction(name, vimContext)
      return
    }
    throw ExException("E130: Unknown function: $name")
  }

  fun storeFunction(declaration: FunctionDeclaration) {
    val scope: Scope = declaration.scope ?: getDefaultFunctionScope()
    when (scope) {
      Scope.GLOBAL_VARIABLE -> {
        if (globalFunctions.containsKey(declaration.name) && !declaration.replaceExisting) {
          throw ExException("E122: Function ${declaration.name} already exists, add ! to replace it")
        } else {
          globalFunctions[declaration.name] = declaration
        }
      }
      Scope.SCRIPT_VARIABLE -> {
        if (declaration.getFirstParentContext() !is Script) {
          throw ExException("E81: Using <SID> not in a script context")
        }

        if (getScriptFunction(declaration.name, declaration) != null && !declaration.replaceExisting) {
          throw ExException("E122: Function ${declaration.name} already exists, add ! to replace it")
        } else {
          storeScriptFunction(declaration)
        }
      }
      else -> throw ExException("E884: Function name cannot contain a colon: ${scope.c}:${declaration.name}")
    }
  }

  fun getFunctionHandler(scope: Scope?, name: String, vimContext: VimLContext): FunctionHandler {
    return getFunctionHandlerOrNull(scope, name, vimContext)
      ?: throw ExException("E117: Unknown function: ${scope?.toString() ?: ""}$name")
  }

  fun getFunctionHandlerOrNull(scope: Scope?, name: String, vimContext: VimLContext): FunctionHandler? {
    if (scope == null) {
      val builtInFunction = getBuiltInFunction(name)
      if (builtInFunction != null) {
        return builtInFunction
      }
    }

    val definedFunction = getUserDefinedFunction(scope, name, vimContext)
    if (definedFunction != null) {
      return DefinedFunctionHandler(definedFunction)
    }
    return null
  }

  fun getUserDefinedFunction(scope: Scope?, name: String, vimContext: VimLContext): FunctionDeclaration? {
    return when (scope) {
      Scope.GLOBAL_VARIABLE -> globalFunctions[name]
      Scope.SCRIPT_VARIABLE -> getScriptFunction(name, vimContext)
      null -> {
        val firstParentContext = vimContext.getFirstParentContext()
        when (firstParentContext) {
          is CommandLineVimLContext -> globalFunctions[name]
          is Script -> globalFunctions[name] ?: getScriptFunction(name, vimContext)
          else -> throw RuntimeException("Unknown parent context")
        }
      }
      else -> null
    }
  }

  fun getBuiltInFunction(name: String): FunctionHandler? {
    return builtInFunctions[name]
  }

  private fun storeScriptFunction(functionDeclaration: FunctionDeclaration) {
    val script = functionDeclaration.getScript() ?: throw ExException("E81: Using <SID> not in a script context")
    script.scriptFunctions[functionDeclaration.name] = functionDeclaration
  }

  private fun getScriptFunction(name: String, vimContext: VimLContext): FunctionDeclaration? {
    val script = vimContext.getScript() ?: throw ExException("E120: Using <SID> not in a script context: s:$name")
    return script.scriptFunctions[name]
  }

  private fun deleteScriptFunction(name: String, vimContext: VimLContext) {
    val script = vimContext.getScript() ?: throw ExException("E81: Using <SID> not in a script context")
    if (script.scriptFunctions[name] != null) {
      script.scriptFunctions[name]!!.isDeleted = true
    }
    script.scriptFunctions.remove(name)
  }

  private fun getDefaultFunctionScope(): Scope {
    return Scope.GLOBAL_VARIABLE
  }

  fun registerHandlers() {
    extensionPoint.extensions().forEach(FunctionBeanClass::register)
  }

  fun addHandler(handlerHolder: FunctionBeanClass) {
    if (handlerHolder.name != null) {
      builtInFunctions[handlerHolder.name!!] = handlerHolder.instance
    } else {
      logger.error("Received function handler with null name")
    }
  }
}
