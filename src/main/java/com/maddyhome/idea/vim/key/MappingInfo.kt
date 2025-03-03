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
package com.maddyhome.idea.vim.key

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.CaretSpecificDataContext
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.action.change.VimRepeater.Extension.argumentCaptured
import com.maddyhome.idea.vim.action.change.VimRepeater.Extension.clean
import com.maddyhome.idea.vim.action.change.VimRepeater.Extension.lastExtensionHandler
import com.maddyhome.idea.vim.action.change.VimRepeater.repeatHandler
import com.maddyhome.idea.vim.command.Argument
import com.maddyhome.idea.vim.command.CommandState
import com.maddyhome.idea.vim.command.SelectionType
import com.maddyhome.idea.vim.command.SelectionType.Companion.fromSubMode
import com.maddyhome.idea.vim.extension.VimExtensionHandler
import com.maddyhome.idea.vim.group.visual.VimSelection
import com.maddyhome.idea.vim.group.visual.VimSelection.Companion.create
import com.maddyhome.idea.vim.helper.ActionExecutor
import com.maddyhome.idea.vim.helper.EditorDataContext
import com.maddyhome.idea.vim.helper.StringHelper.parseKeys
import com.maddyhome.idea.vim.helper.StringHelper.toKeyNotation
import com.maddyhome.idea.vim.helper.VimNlsSafe
import com.maddyhome.idea.vim.helper.commandState
import com.maddyhome.idea.vim.helper.subMode
import com.maddyhome.idea.vim.helper.vimSelectionStart
import com.maddyhome.idea.vim.listener.SelectionVimListenerSuppressor
import com.maddyhome.idea.vim.newapi.IjVimEditor
import com.maddyhome.idea.vim.newapi.vim
import com.maddyhome.idea.vim.vimscript.model.CommandLineVimLContext
import com.maddyhome.idea.vim.vimscript.model.expressions.Expression
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import kotlin.math.min

/**
 * @author vlan
 */
sealed class MappingInfo(val fromKeys: List<KeyStroke>, val isRecursive: Boolean, val owner: MappingOwner) :
  Comparable<MappingInfo> {

  @VimNlsSafe
  abstract fun getPresentableString(): String

  abstract fun execute(editor: Editor, context: DataContext)

  override fun compareTo(other: MappingInfo): Int {
    val size = fromKeys.size
    val otherSize = other.fromKeys.size
    val n = min(size, otherSize)
    for (i in 0 until n) {
      val diff = compareKeys(fromKeys[i], other.fromKeys[i])
      if (diff != 0) return diff
    }
    return size - otherSize
  }

  private fun compareKeys(key1: KeyStroke, key2: KeyStroke): Int {
    val c1 = key1.keyChar
    val c2 = key2.keyChar
    return when {
      c1 == KeyEvent.CHAR_UNDEFINED && c2 == KeyEvent.CHAR_UNDEFINED -> {
        val keyCodeDiff = key1.keyCode - key2.keyCode
        if (keyCodeDiff != 0) keyCodeDiff else key1.modifiers - key2.modifiers
      }
      c1 == KeyEvent.CHAR_UNDEFINED -> -1
      c2 == KeyEvent.CHAR_UNDEFINED -> 1
      else -> c1 - c2
    }
  }
}

class ToKeysMappingInfo(
  val toKeys: List<KeyStroke>,
  fromKeys: List<KeyStroke>,
  isRecursive: Boolean,
  owner: MappingOwner,
) : MappingInfo(fromKeys, isRecursive, owner) {
  override fun getPresentableString(): String = toKeyNotation(toKeys)

  override fun execute(editor: Editor, context: DataContext) {
    LOG.debug("Executing 'ToKeys' mapping info...")
    val editorDataContext = EditorDataContext.init(editor, context)
    val fromIsPrefix = KeyHandler.isPrefix(fromKeys, toKeys)
    var first = true
    for (keyStroke in toKeys) {
      val recursive = isRecursive && !(first && fromIsPrefix)
      val keyHandler = KeyHandler.getInstance()
      keyHandler.handleKey(editor.vim, keyStroke, editorDataContext.vim, recursive, false)
      first = false
    }
  }

  companion object {
    private val LOG = logger<ToKeysMappingInfo>()
  }
}

class ToExpressionMappingInfo(
  private val toExpression: Expression,
  fromKeys: List<KeyStroke>,
  isRecursive: Boolean,
  owner: MappingOwner,
  private val originalString: String,
) : MappingInfo(fromKeys, isRecursive, owner) {
  override fun getPresentableString(): String = originalString

  override fun execute(editor: Editor, context: DataContext) {
    LOG.debug("Executing 'ToExpression' mapping info...")
    val editorDataContext = EditorDataContext.init(editor, context)
    val toKeys = parseKeys(toExpression.evaluate(editor, context, CommandLineVimLContext).toString())
    val fromIsPrefix = KeyHandler.isPrefix(fromKeys, toKeys)
    var first = true
    for (keyStroke in toKeys) {
      val recursive = isRecursive && !(first && fromIsPrefix)
      val keyHandler = KeyHandler.getInstance()
      keyHandler.handleKey(editor.vim, keyStroke, editorDataContext.vim, recursive, false)
      first = false
    }
  }

  companion object {
    private val LOG = logger<ToExpressionMappingInfo>()
  }
}

class ToHandlerMappingInfo(
  private val extensionHandler: VimExtensionHandler,
  fromKeys: List<KeyStroke>,
  isRecursive: Boolean,
  owner: MappingOwner,
) : MappingInfo(fromKeys, isRecursive, owner) {
  override fun getPresentableString(): String = "call ${extensionHandler.javaClass.canonicalName}"

  override fun execute(editor: Editor, context: DataContext) {
    LOG.debug("Executing 'ToHandler' mapping info...")
    val processor = CommandProcessor.getInstance()
    val commandState = CommandState.getInstance(editor)

    // Cache isOperatorPending in case the extension changes the mode while moving the caret
    // See CommonExtensionTest
    // TODO: Is this legal? Should we assert in this case?

    // Cache isOperatorPending in case the extension changes the mode while moving the caret
    // See CommonExtensionTest
    // TODO: Is this legal? Should we assert in this case?
    val shouldCalculateOffsets: Boolean = commandState.isOperatorPending

    val startOffsets: Map<Caret, Int> = editor.caretModel.allCarets.associateWith { it.offset }

    if (extensionHandler.isRepeatable) {
      clean()
    }

    val handler = extensionHandler
    if (handler is VimExtensionHandler.WithCallback) {
      handler._backingFunction = Runnable {
        myFun(shouldCalculateOffsets, editor, startOffsets)


        if (shouldCalculateOffsets) {
          invokeLater {
            KeyHandler.getInstance().finishedCommandPreparation(
              IjVimEditor(editor),
              context.vim, CommandState.getInstance(editor), CommandState.getInstance(editor).commandBuilder, null, false)
          }
        }
      }
    }

    processor.executeCommand(
      editor.project, { extensionHandler.execute(editor, context) },
      "Vim " + extensionHandler.javaClass.simpleName, null
    )

    if (extensionHandler.isRepeatable) {
      lastExtensionHandler = extensionHandler
      argumentCaptured = null
      repeatHandler = true
    }

    if (handler !is VimExtensionHandler.WithCallback) {
      myFun(shouldCalculateOffsets, editor, startOffsets)
    }
  }

  companion object {
    private val LOG = logger<ToHandlerMappingInfo>()

    private fun myFun(
      shouldCalculateOffsets: Boolean,
      editor: Editor,
      startOffsets: Map<Caret, Int>,
    ) {
      val commandState = editor.commandState
      if (shouldCalculateOffsets && !commandState.commandBuilder.hasCurrentCommandPartArgument()) {
        val offsets: MutableMap<Caret, VimSelection> = HashMap()
        for (caret in editor.caretModel.allCarets) {
          var startOffset = startOffsets[caret]
          if (caret.hasSelection()) {
            val vimSelection = create(caret.vimSelectionStart, caret.offset, fromSubMode(editor.subMode), editor)
            offsets[caret] = vimSelection
            commandState.popModes()
          } else if (startOffset != null && startOffset != caret.offset) {
            // Command line motions are always characterwise exclusive
            var endOffset = caret.offset
            if (startOffset < endOffset) {
              endOffset -= 1
            } else {
              startOffset -= 1
            }
            val vimSelection = create(startOffset, endOffset, SelectionType.CHARACTER_WISE, editor)
            offsets[caret] = vimSelection
            SelectionVimListenerSuppressor.lock().use {
              // Move caret to the initial offset for better undo action
              //  This is not a necessary thing, but without it undo action look less convenient
              editor.caretModel.moveToOffset(startOffset)
            }
          }
        }
        if (offsets.isNotEmpty()) {
          commandState.commandBuilder.completeCommandPart(Argument(offsets))
        }
      }
    }
  }
}

class ToActionMappingInfo(
  val action: String,
  fromKeys: List<KeyStroke>,
  isRecursive: Boolean,
  owner: MappingOwner,
) : MappingInfo(fromKeys, isRecursive, owner) {
  override fun getPresentableString(): String = "action $action"

  override fun execute(editor: Editor, context: DataContext) {
    LOG.debug("Executing 'ToAction' mapping...")
    val editorDataContext = EditorDataContext.init(editor, context)
    val dataContext = CaretSpecificDataContext(editorDataContext, editor.caretModel.currentCaret)
    ActionExecutor.executeAction(action, dataContext)
  }

  companion object {
    private val LOG = logger<ToActionMappingInfo>()
  }
}
