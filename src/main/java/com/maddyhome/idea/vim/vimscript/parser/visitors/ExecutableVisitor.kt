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

package com.maddyhome.idea.vim.vimscript.parser.visitors

import com.maddyhome.idea.vim.vimscript.model.Executable
import com.maddyhome.idea.vim.vimscript.model.expressions.Expression
import com.maddyhome.idea.vim.vimscript.model.expressions.OneElementSublistExpression
import com.maddyhome.idea.vim.vimscript.model.expressions.Scope
import com.maddyhome.idea.vim.vimscript.model.expressions.SimpleExpression
import com.maddyhome.idea.vim.vimscript.model.expressions.Variable
import com.maddyhome.idea.vim.vimscript.model.statements.AnonymousFunctionDeclaration
import com.maddyhome.idea.vim.vimscript.model.statements.CatchBlock
import com.maddyhome.idea.vim.vimscript.model.statements.FinallyBlock
import com.maddyhome.idea.vim.vimscript.model.statements.FinishStatement
import com.maddyhome.idea.vim.vimscript.model.statements.FunctionDeclaration
import com.maddyhome.idea.vim.vimscript.model.statements.FunctionFlag
import com.maddyhome.idea.vim.vimscript.model.statements.IfStatement
import com.maddyhome.idea.vim.vimscript.model.statements.ReturnStatement
import com.maddyhome.idea.vim.vimscript.model.statements.ThrowStatement
import com.maddyhome.idea.vim.vimscript.model.statements.TryBlock
import com.maddyhome.idea.vim.vimscript.model.statements.TryStatement
import com.maddyhome.idea.vim.vimscript.model.statements.loops.BreakStatement
import com.maddyhome.idea.vim.vimscript.model.statements.loops.ContinueStatement
import com.maddyhome.idea.vim.vimscript.model.statements.loops.ForLoop
import com.maddyhome.idea.vim.vimscript.model.statements.loops.ForLoopWithList
import com.maddyhome.idea.vim.vimscript.model.statements.loops.WhileLoop
import com.maddyhome.idea.vim.vimscript.parser.generated.VimscriptBaseVisitor
import com.maddyhome.idea.vim.vimscript.parser.generated.VimscriptParser

object ExecutableVisitor : VimscriptBaseVisitor<Executable>() {

  override fun visitBlockMember(ctx: VimscriptParser.BlockMemberContext): Executable? {
    return when {
      ctx.command() != null -> CommandVisitor.visit(ctx.command())
      ctx.breakStatement() != null -> BreakStatement
      ctx.continueStatement() != null -> ContinueStatement
      ctx.finishStatement() != null -> FinishStatement
      ctx.returnStatement() != null -> ReturnStatement(ExpressionVisitor.visit(ctx.returnStatement().expr()))
      ctx.ifStatement() != null -> visitIfStatement(ctx.ifStatement())
      ctx.forLoop() != null -> visitForLoop(ctx.forLoop())
      ctx.whileLoop() != null -> visitWhileLoop(ctx.whileLoop())
      ctx.functionDefinition() != null -> visitFunctionDefinition(ctx.functionDefinition())
      ctx.throwStatement() != null -> visitThrowStatement(ctx.throwStatement())
      ctx.tryStatement() != null -> visitTryStatement(ctx.tryStatement())
      else -> null
    }
  }

  override fun visitWhileLoop(ctx: VimscriptParser.WhileLoopContext): Executable {
    val condition: Expression = ExpressionVisitor.visit(ctx.expr())
    val body: List<Executable> = ctx.blockMember().mapNotNull { visitBlockMember(it) }
    return WhileLoop(condition, body)
  }

  override fun visitForLoop(ctx: VimscriptParser.ForLoopContext): Executable {
    val iterable = ExpressionVisitor.visit(ctx.expr())
    val body = ctx.blockMember().mapNotNull { visitBlockMember(it) }
    return if (ctx.argumentsDeclaration() == null) {
      val variable = Variable(Scope.getByValue(ctx.variableScope()?.text ?: ""), ctx.variableName().text)
      ForLoop(variable, iterable, body)
    } else {
      val variables = ctx.argumentsDeclaration().variableName().map { it.text }
      ForLoopWithList(variables, iterable, body)
    }
  }

  override fun visitFunctionDefinition(ctx: VimscriptParser.FunctionDefinitionContext): Executable {
    val functionScope = if (ctx.functionScope() != null) Scope.getByValue(ctx.functionScope().text) else null
    val args = ctx.argumentsDeclaration().variableName().map { it.text }
    val defaultArgs = ctx.argumentsDeclaration().defaultValue()
      .map { Pair<String, Expression>(it.variableName().text, ExpressionVisitor.visit(it.expr())) }
    val body = ctx.blockMember().mapNotNull { visitBlockMember(it) }
    val replaceExisting = ctx.replace != null
    val flags = mutableSetOf<FunctionFlag?>()
    val hasOptionalArguments = ctx.argumentsDeclaration().ETC() != null
    for (flag in ctx.functionFlag()) {
      flags.add(FunctionFlag.getByName(flag.text))
    }
    return if (ctx.functionName() != null) {
      val functionName = ctx.functionName().text
      FunctionDeclaration(functionScope, functionName, args, defaultArgs, body, replaceExisting, flags.filterNotNull().toSet(), hasOptionalArguments)
    } else {
      var sublistExpression = OneElementSublistExpression(SimpleExpression(ctx.literalDictionaryKey(1).text), Variable(functionScope, ctx.literalDictionaryKey(0).text))
      for (i in 2 until ctx.literalDictionaryKey().size) {
        sublistExpression = OneElementSublistExpression(SimpleExpression(ctx.literalDictionaryKey(i).text), sublistExpression)
      }
      AnonymousFunctionDeclaration(sublistExpression, args, defaultArgs, body, replaceExisting, flags.filterNotNull().toSet(), hasOptionalArguments)
    }
  }

  override fun visitTryStatement(ctx: VimscriptParser.TryStatementContext): Executable {
    val tryBlock = TryBlock(ctx.tryBlock().blockMember().mapNotNull { visitBlockMember(it) })
    val catchBlocks: MutableList<CatchBlock> = mutableListOf()
    for (catchBlock in ctx.catchBlock()) {
      catchBlocks.add(
        CatchBlock(
          catchBlock.pattern()?.patternBody()?.text ?: ".",
          catchBlock.blockMember().mapNotNull { visitBlockMember(it) }
        )
      )
    }
    var finallyBlock: FinallyBlock? = null
    if (ctx.finallyBlock() != null) {
      finallyBlock = FinallyBlock(ctx.finallyBlock().blockMember().mapNotNull { visitBlockMember(it) })
    }
    return TryStatement(tryBlock, catchBlocks, finallyBlock)
  }

  override fun visitReturnStatement(ctx: VimscriptParser.ReturnStatementContext): Executable {
    val expression: Expression = ExpressionVisitor.visit(ctx.expr())
    return ReturnStatement(expression)
  }

  override fun visitThrowStatement(ctx: VimscriptParser.ThrowStatementContext): Executable {
    val expression: Expression = ExpressionVisitor.visit(ctx.expr())
    return ThrowStatement(expression)
  }

  override fun visitIfStatement(ctx: VimscriptParser.IfStatementContext): Executable {
    val conditionToBody: MutableList<Pair<Expression, List<Executable>>> = mutableListOf()
    conditionToBody.add(
      ExpressionVisitor.visit(ctx.ifBlock().expr()) to ctx.ifBlock().blockMember()
        .mapNotNull { visitBlockMember(it) }
    )
    if (ctx.elifBlock() != null) {
      conditionToBody.addAll(
        ctx.elifBlock().map {
          ExpressionVisitor.visit(it.expr()) to it.blockMember().mapNotNull { it2 -> visitBlockMember(it2) }
        }
      )
    }
    if (ctx.elseBlock() != null) {
      conditionToBody.add(
        SimpleExpression(1) to ctx.elseBlock().blockMember()
          .mapNotNull { visitBlockMember(it) }
      )
    }
    return IfStatement(conditionToBody)
  }
}
