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

package com.maddyhome.idea.vim.group.visual

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.editor.Editor
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.command.CommandState
import com.maddyhome.idea.vim.helper.EditorDataContext
import com.maddyhome.idea.vim.helper.commandState
import com.maddyhome.idea.vim.helper.exitSelectMode
import com.maddyhome.idea.vim.helper.exitVisualMode
import com.maddyhome.idea.vim.helper.hasVisualSelection
import com.maddyhome.idea.vim.helper.inInsertMode
import com.maddyhome.idea.vim.helper.inNormalMode
import com.maddyhome.idea.vim.helper.inSelectMode
import com.maddyhome.idea.vim.helper.inVisualMode
import com.maddyhome.idea.vim.helper.isIdeaVimDisabledHere
import com.maddyhome.idea.vim.helper.isTemplateActive
import com.maddyhome.idea.vim.helper.mode
import com.maddyhome.idea.vim.helper.popAllModes
import com.maddyhome.idea.vim.listener.VimListenerManager
import com.maddyhome.idea.vim.newapi.IjVimEditor
import com.maddyhome.idea.vim.newapi.vim
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.options.helpers.IdeaRefactorModeHelper
import com.maddyhome.idea.vim.vimscript.services.OptionConstants
import com.maddyhome.idea.vim.vimscript.services.OptionService

object IdeaSelectionControl {
  /**
   * This method should be in sync with [predictMode]
   *
   * Control unexpected (non vim) selection change and adjust a mode to it. The new mode is not enabled immediately,
   *   but with some delay (using [VimVisualTimer])
   *
   * See [VimVisualTimer] to more info.
   */
  fun controlNonVimSelectionChange(
    editor: Editor,
    selectionSource: VimListenerManager.SelectionSource = VimListenerManager.SelectionSource.OTHER,
  ) {
    VimVisualTimer.singleTask(editor.mode) { initialMode ->

      if (editor.isIdeaVimDisabledHere) return@singleTask

      logger.debug("Adjust non-vim selection. Source: $selectionSource, initialMode: $initialMode")

      // Perform logic in one of the next cases:
      //  - There was no selection and now it is
      //  - There was a selection and now it doesn't exist
      //  - There was a selection and now it exists as well (transforming char selection to line selection, for example)
      if (initialMode?.hasVisualSelection == false && !editor.selectionModel.hasSelection(true)) {
        logger.trace { "Exiting without selection adjusting" }
        return@singleTask
      }

      if (editor.selectionModel.hasSelection(true)) {
        if (dontChangeMode(editor)) {
          IdeaRefactorModeHelper.correctSelection(editor)
          logger.trace { "Selection corrected for refactoring" }
          return@singleTask
        }

        logger.debug("Some carets have selection. State before adjustment: ${editor.commandState.toSimpleString()}")

        editor.popAllModes()

        activateMode(editor, chooseSelectionMode(editor, selectionSource, true))
      } else {
        logger.debug("None of carets have selection. State before adjustment: ${editor.commandState.toSimpleString()}")
        if (editor.inVisualMode) editor.exitVisualMode()
        if (editor.inSelectMode) editor.exitSelectMode(false)

        if (editor.inNormalMode) {
          activateMode(editor, chooseNonSelectionMode(editor))
        }
      }

      KeyHandler.getInstance().reset(editor.vim)
      logger.debug("${editor.mode} is enabled")
    }
  }

  /**
   * This method should be in sync with [controlNonVimSelectionChange]
   *
   * Predict the mode after changing visual selection. The prediction will be correct if there is only one sequential
   *   visual change (e.g. somebody executed "extract selection" action. The prediction can be wrong in case of
   *   multiple sequential visual changes (e.g. "technical" visual selection during typing in japanese)
   *
   * This method is created to improve user experience. It allows avoiding delay in some operations
   *   (because [controlNonVimSelectionChange] is not executed immediately)
   */
  fun predictMode(editor: Editor, selectionSource: VimListenerManager.SelectionSource): CommandState.Mode {
    if (editor.selectionModel.hasSelection(true)) {
      if (dontChangeMode(editor)) return editor.mode
      return chooseSelectionMode(editor, selectionSource, false)
    } else {
      return chooseNonSelectionMode(editor)
    }
  }

  private fun activateMode(editor: Editor, mode: CommandState.Mode) {
    when (mode) {
      CommandState.Mode.VISUAL -> VimPlugin.getVisualMotion()
        .enterVisualMode(editor, VimPlugin.getVisualMotion().autodetectVisualSubmode(editor))
      CommandState.Mode.SELECT -> VimPlugin.getVisualMotion()
        .enterSelectMode(editor, VimPlugin.getVisualMotion().autodetectVisualSubmode(editor))
      CommandState.Mode.INSERT -> VimPlugin.getChange().insertBeforeCursor(
        editor,
        EditorDataContext.init(editor)
      )
      CommandState.Mode.COMMAND -> Unit
      else -> error("Unexpected mode: $mode")
    }
  }

  private fun dontChangeMode(editor: Editor): Boolean =
    editor.isTemplateActive() && (IdeaRefactorModeHelper.keepMode() || editor.mode.hasVisualSelection)

  private fun chooseNonSelectionMode(editor: Editor): CommandState.Mode {
    val templateActive = editor.isTemplateActive()
    if (templateActive && editor.inNormalMode || editor.inInsertMode) {
      return CommandState.Mode.INSERT
    }
    return CommandState.Mode.COMMAND
  }

  private fun chooseSelectionMode(
    editor: Editor,
    selectionSource: VimListenerManager.SelectionSource,
    logReason: Boolean,
  ): CommandState.Mode {
    val selectmode = (VimPlugin.getOptionService().getOptionValue(OptionService.Scope.LOCAL(IjVimEditor(editor)), OptionConstants.selectmodeName) as VimString).value
    return when {
      editor.isOneLineMode -> {
        if (logReason) logger.debug("Enter select mode. Reason: one line mode")
        CommandState.Mode.SELECT
      }
      selectionSource == VimListenerManager.SelectionSource.MOUSE && OptionConstants.selectmode_mouse in selectmode -> {
        if (logReason) logger.debug("Enter select mode. Selection source is mouse and selectMode option has mouse")
        CommandState.Mode.SELECT
      }
      editor.isTemplateActive() && IdeaRefactorModeHelper.selectMode() -> {
        if (logReason) logger.debug("Enter select mode. Template is active and selectMode has template")
        CommandState.Mode.SELECT
      }
      selectionSource == VimListenerManager.SelectionSource.OTHER &&
        OptionConstants.selectmode_ideaselection in (VimPlugin.getOptionService().getOptionValue(OptionService.Scope.GLOBAL, OptionConstants.selectmodeName) as VimString).value -> {
        if (logReason) logger.debug("Enter select mode. Selection source is OTHER and selectMode has refactoring")
        CommandState.Mode.SELECT
      }
      else -> {
        if (logReason) logger.debug("Enter visual mode")
        CommandState.Mode.VISUAL
      }
    }
  }

  private val logger = Logger.getInstance(IdeaSelectionControl::class.java)
}
