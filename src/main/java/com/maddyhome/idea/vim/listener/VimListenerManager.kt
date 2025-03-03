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

package com.maddyhome.idea.vim.listener

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.util.ExceptionUtil
import com.maddyhome.idea.vim.EventFacade
import com.maddyhome.idea.vim.KeyHandler
import com.maddyhome.idea.vim.VimKeyListener
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.VimTypedActionHandler
import com.maddyhome.idea.vim.command.CommandState
import com.maddyhome.idea.vim.ex.ExOutputModel
import com.maddyhome.idea.vim.group.EditorGroup
import com.maddyhome.idea.vim.group.FileGroup
import com.maddyhome.idea.vim.group.MotionGroup
import com.maddyhome.idea.vim.group.SearchGroup
import com.maddyhome.idea.vim.group.visual.IdeaSelectionControl
import com.maddyhome.idea.vim.group.visual.VimVisualTimer
import com.maddyhome.idea.vim.group.visual.moveCaretOneCharLeftFromSelectionEnd
import com.maddyhome.idea.vim.group.visual.vimSetSystemSelectionSilently
import com.maddyhome.idea.vim.helper.EditorHelper
import com.maddyhome.idea.vim.helper.GuicursorChangeListener
import com.maddyhome.idea.vim.helper.UpdatesChecker
import com.maddyhome.idea.vim.helper.exitSelectMode
import com.maddyhome.idea.vim.helper.exitVisualMode
import com.maddyhome.idea.vim.helper.forceBarCursor
import com.maddyhome.idea.vim.helper.inSelectMode
import com.maddyhome.idea.vim.helper.inVisualMode
import com.maddyhome.idea.vim.helper.isEndAllowed
import com.maddyhome.idea.vim.helper.isIdeaVimDisabledHere
import com.maddyhome.idea.vim.helper.localEditors
import com.maddyhome.idea.vim.helper.moveToInlayAwareOffset
import com.maddyhome.idea.vim.helper.subMode
import com.maddyhome.idea.vim.helper.updateCaretsVisualAttributes
import com.maddyhome.idea.vim.helper.vimDisabled
import com.maddyhome.idea.vim.helper.vimLastColumn
import com.maddyhome.idea.vim.listener.MouseEventsDataHolder.skipEvents
import com.maddyhome.idea.vim.listener.MouseEventsDataHolder.skipNDragEvents
import com.maddyhome.idea.vim.listener.VimListenerManager.EditorListeners.add
import com.maddyhome.idea.vim.listener.VimListenerManager.EditorListeners.remove
import com.maddyhome.idea.vim.newapi.vim
import com.maddyhome.idea.vim.option.StrictMode
import com.maddyhome.idea.vim.ui.ShowCmdOptionChangeListener
import com.maddyhome.idea.vim.ui.ex.ExEntryPanel
import com.maddyhome.idea.vim.vimscript.model.options.helpers.KeywordOptionChangeListener
import com.maddyhome.idea.vim.vimscript.services.OptionConstants
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * @author Alex Plate
 */

object VimListenerManager {

  private val logger = Logger.getInstance(VimListenerManager::class.java)

  fun turnOn() {
    GlobalListeners.enable()
    EditorListeners.addAll()
  }

  fun turnOff() {
    GlobalListeners.disable()
    EditorListeners.removeAll()
  }

  object GlobalListeners {
    fun enable() {
      val typedAction = TypedAction.getInstance()
      if (typedAction.rawHandler !is VimTypedActionHandler) {
        // Actually this if should always be true, but just as protection
        EventFacade.getInstance().setupTypedActionHandler(VimTypedActionHandler(typedAction.rawHandler))
      } else {
        StrictMode.fail("typeAction expected to be non-vim.")
      }

      VimPlugin.getOptionService().addListener(OptionConstants.numberName, EditorGroup.NumberChangeListener.INSTANCE)
      VimPlugin.getOptionService().addListener(OptionConstants.relativenumberName, EditorGroup.NumberChangeListener.INSTANCE)
      VimPlugin.getOptionService().addListener(OptionConstants.scrolloffName, MotionGroup.ScrollOptionsChangeListener.INSTANCE)
      VimPlugin.getOptionService().addListener(OptionConstants.showcmdName, ShowCmdOptionChangeListener)
      VimPlugin.getOptionService().addListener(OptionConstants.guicursorName, GuicursorChangeListener)
      VimPlugin.getOptionService().addListener(OptionConstants.iskeywordName, KeywordOptionChangeListener, true)

      EventFacade.getInstance().addEditorFactoryListener(VimEditorFactoryListener, VimPlugin.getInstance())

      EditorFactory.getInstance().eventMulticaster.addCaretListener(VimCaretListener, VimPlugin.getInstance())
    }

    fun disable() {
      EventFacade.getInstance().restoreTypedActionHandler()

      VimPlugin.getOptionService().removeListener(OptionConstants.numberName, EditorGroup.NumberChangeListener.INSTANCE)
      VimPlugin.getOptionService().removeListener(OptionConstants.relativenumberName, EditorGroup.NumberChangeListener.INSTANCE)
      VimPlugin.getOptionService().removeListener(OptionConstants.scrolloffName, MotionGroup.ScrollOptionsChangeListener.INSTANCE)
      VimPlugin.getOptionService().removeListener(OptionConstants.showcmdName, ShowCmdOptionChangeListener)
      VimPlugin.getOptionService().removeListener(OptionConstants.guicursorName, GuicursorChangeListener)
      VimPlugin.getOptionService().removeListener(OptionConstants.iskeywordName, KeywordOptionChangeListener)

      EventFacade.getInstance().removeEditorFactoryListener(VimEditorFactoryListener)

      EditorFactory.getInstance().eventMulticaster.removeCaretListener(VimCaretListener)
    }
  }

  object EditorListeners {
    fun addAll() {
      localEditors().forEach { editor ->
        this.add(editor)
      }
    }

    fun removeAll() {
      localEditors().forEach { editor ->
        this.remove(editor, false)
      }
    }

    fun add(editor: Editor) {

      editor.contentComponent.addKeyListener(VimKeyListener)
      val eventFacade = EventFacade.getInstance()
      eventFacade.addEditorMouseListener(editor, EditorMouseHandler)
      eventFacade.addEditorMouseMotionListener(editor, EditorMouseHandler)
      eventFacade.addEditorSelectionListener(editor, EditorSelectionHandler)
      eventFacade.addComponentMouseListener(editor.contentComponent, ComponentMouseListener)

      VimPlugin.getEditor().editorCreated(editor)

      VimPlugin.getChange().editorCreated(editor)
    }

    fun remove(editor: Editor, isReleased: Boolean) {

      editor.contentComponent.removeKeyListener(VimKeyListener)
      val eventFacade = EventFacade.getInstance()
      eventFacade.removeEditorMouseListener(editor, EditorMouseHandler)
      eventFacade.removeEditorMouseMotionListener(editor, EditorMouseHandler)
      eventFacade.removeEditorSelectionListener(editor, EditorSelectionHandler)
      eventFacade.removeComponentMouseListener(editor.contentComponent, ComponentMouseListener)

      VimPlugin.getEditorIfCreated()?.editorDeinit(editor, isReleased)

      VimPlugin.getChange().editorReleased(editor)
    }
  }

  object VimCaretListener : CaretListener {
    override fun caretAdded(event: CaretEvent) {
      if (vimDisabled(event.editor)) return
      event.editor.updateCaretsVisualAttributes()
    }

    override fun caretRemoved(event: CaretEvent) {
      if (vimDisabled(event.editor)) return
      event.editor.updateCaretsVisualAttributes()
    }
  }

  class VimFileEditorManagerListener : FileEditorManagerListener {
    override fun selectionChanged(event: FileEditorManagerEvent) {
      if (!VimPlugin.isEnabled()) return
      MotionGroup.fileEditorManagerSelectionChangedCallback(event)
      FileGroup.fileEditorManagerSelectionChangedCallback(event)
      SearchGroup.fileEditorManagerSelectionChangedCallback(event)
    }
  }

  private object VimEditorFactoryListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
      add(event.editor)
      UpdatesChecker.check()
    }

    override fun editorReleased(event: EditorFactoryEvent) {
      remove(event.editor, true)
      VimPlugin.getMark().editorReleased(event)
    }
  }

  private object EditorSelectionHandler : SelectionListener {
    private var myMakingChanges = false

    /**
     * This event is executed for each caret using [com.intellij.openapi.editor.CaretModel.runForEachCaret]
     */
    override fun selectionChanged(selectionEvent: SelectionEvent) {
      if (selectionEvent.editor.isIdeaVimDisabledHere) return
      val editor = selectionEvent.editor
      val document = editor.document

      logger.trace { "Selection changed" }
      logger.trace { ExceptionUtil.currentStackTrace() }

      //region Not selected last character protection
      // Here is currently a bug in IJ for IdeaVim. If you start selection right from the line end, then
      //  move to the left, the last character remains unselected.
      //  It's not clear why this happens, but this code fixes it.
      val caret = editor.caretModel.currentCaret
      val lineEnd = EditorHelper.getLineEndForOffset(editor, caret.offset)
      val lineStart = EditorHelper.getLineStartForOffset(editor, caret.offset)
      if (skipNDragEvents < skipEvents &&
        lineEnd != lineStart &&
        selectionEvent.newRange.startOffset == selectionEvent.newRange.endOffset &&
        selectionEvent.newRange.startOffset == lineEnd - 1 &&
        selectionEvent.newRange.startOffset == caret.offset
      ) {
        caret.setSelection(lineEnd, lineEnd - 1)
      }
      //endregion

      if (SelectionVimListenerSuppressor.isNotLocked) {
        logger.debug("Adjust non vim selection change")
        IdeaSelectionControl.controlNonVimSelectionChange(editor)
      }

      if (myMakingChanges || document is DocumentEx && document.isInEventsHandling) {
        return
      }

      myMakingChanges = true
      try {
        // Synchronize selections between editors
        val newRange = selectionEvent.newRange
        for (e in localEditors(document)) {
          if (e != editor) {
            e.selectionModel.vimSetSystemSelectionSilently(newRange.startOffset, newRange.endOffset)
          }
        }
      } finally {
        myMakingChanges = false
      }
    }
  }

  private object EditorMouseHandler : EditorMouseListener, EditorMouseMotionListener {
    private var mouseDragging = false
    private var cutOffFixed = false

    override fun mouseDragged(e: EditorMouseEvent) {
      if (e.editor.isIdeaVimDisabledHere) return

      val caret = e.editor.caretModel.primaryCaret

      clearFirstSelectionEvents(e)

      if (mouseDragging && caret.hasSelection()) {
        /**
         * We force the bar caret while dragging because it matches IntelliJ's selection model better.
         * * Vim's drag selection is based on character bounding boxes. When 'selection' is set to "inclusive" (the
         *   default), Vim selects a character when the mouse cursor drags the text caret into its bounding box (LTR).
         *   The character at the text caret is selected and the block caret is drawn to cover the character (the bar
         *   caret would be between the selection and the last character of the selection, which is weird). See "v" in
         *   'guicursor'. When 'selection' is "exclusive", Vim will select a character when the mouse cursor drags the
         *   text caret out of its bounding box. The character at the text caret is not selected and the bar caret is
         *   drawn at the start of this character to make it more obvious that it is unselected. See "ve" in
         *   'guicursor'.
         * * IntelliJ's selection is based on character mid-points. E.g. the caret is moved to the start of offset 2
         *   when the second half of offset 1 is clicked, and a character is selected when the mouse is moved from the
         *   first half to the second half. This means:
         *   1) While dragging, the selection is always exclusive - the character at the text caret is not selected. We
         *   convert to an inclusive selection when the mouse is released, by moving back one character. It makes
         *   sense to match Vim's bar caret here.
         *   2) An exclusive selection should trail behind the mouse cursor, but IntelliJ doesn't, because the selection
         *   boundaries are mid-points - the text caret can be in front of/to the right of the mouse cursor (LTR).
         *   Using a block caret would push the block further out passed the selection and the mouse cursor, and
         *   feels wrong. The bar caret is a better user experience.
         *   RTL probably introduces other fun issues
         * We can implement inclusive/exclusive 'selection' with normal text movement, but unless we can change the way
         * selection works while dragging, I don't think we can match Vim's selection behaviour exactly.
         */
        caret.forceBarCursor()

        if (!cutOffFixed && ComponentMouseListener.cutOffEnd) {
          cutOffFixed = true
          SelectionVimListenerSuppressor.lock().use {
            if (caret.selectionEnd == e.editor.document.getLineEndOffset(caret.logicalPosition.line) - 1 &&
              caret.leadSelectionOffset == caret.selectionEnd
            ) {
              // A small but important customization. Because IdeaVim doesn't allow to put the caret on the line end,
              //   the selection can omit the last character if the selection was started in the middle on the
              //   last character in line and has a negative direction.
              caret.setSelection(caret.selectionStart, caret.selectionEnd + 1)
            }
            // This is the same correction, but for the newer versions of the IDE: 213+
            if (caret.selectionEnd == e.editor.document.getLineEndOffset(caret.logicalPosition.line) &&
              caret.selectionEnd == caret.selectionStart + 1
            ) {
              caret.setSelection(caret.selectionEnd, caret.selectionEnd)
            }
          }
        }
      }
      skipNDragEvents -= 1
    }

    /**
     * When user places the caret, sometimes they perform a small drag. This doesn't affect clear IJ, but with IdeaVim
     * it may introduce unwanted selection. Here we remove any selection if "dragging" happens for less than 3 events.
     * This is because the first click moves the caret passed the end of the line, is then received in
     * [ComponentMouseListener] and the caret is moved back to the start of the last character of the line. If there is
     * a drag, this translates to a selection of the last character. In this case, remove the selection.
     * We force the bar caret simply because it looks better - the block caret is dragged to the end, becomes a less
     * intrusive bar caret and snaps back to the last character (and block caret) when the mouse is released.
     * TODO: Vim supports selection of the character after the end of line
     * (Both with mouse and with v$. IdeaVim treats v$ as an exclusive selection)
     */
    private fun clearFirstSelectionEvents(e: EditorMouseEvent) {
      if (skipNDragEvents > 0) {
        logger.debug("Mouse dragging")
        VimVisualTimer.swingTimer?.stop()
        if (!mouseDragging) {
          SelectionVimListenerSuppressor.lock()
        }
        mouseDragging = true

        val caret = e.editor.caretModel.primaryCaret
        if (onLineEnd(caret)) {
          SelectionVimListenerSuppressor.lock().use {
            caret.removeSelection()
            caret.forceBarCursor()
          }
        }
      }
    }

    private fun onLineEnd(caret: Caret): Boolean {
      val editor = caret.editor
      val lineEnd = EditorHelper.getLineEndForOffset(editor, caret.offset)
      val lineStart = EditorHelper.getLineStartForOffset(editor, caret.offset)
      return caret.offset == lineEnd && lineEnd != lineStart && caret.offset - 1 == caret.selectionStart && caret.offset == caret.selectionEnd
    }

    override fun mousePressed(event: EditorMouseEvent) {
      if (event.editor.isIdeaVimDisabledHere) return

      skipNDragEvents = skipEvents
      SelectionVimListenerSuppressor.reset()
    }

    /**
     * This method may not be called
     * Known cases:
     * - Click-hold and close editor (ctrl-w)
     * - Click-hold and switch editor (ctrl-tab)
     */
    override fun mouseReleased(event: EditorMouseEvent) {
      if (event.editor.isIdeaVimDisabledHere) return

      SelectionVimListenerSuppressor.unlock()

      clearFirstSelectionEvents(event)
      skipNDragEvents = skipEvents
      if (mouseDragging) {
        logger.debug("Release mouse after dragging")
        val editor = event.editor
        val caret = editor.caretModel.primaryCaret
        SelectionVimListenerSuppressor.lock().use {
          val predictedMode = IdeaSelectionControl.predictMode(editor, SelectionSource.MOUSE)
          IdeaSelectionControl.controlNonVimSelectionChange(editor, SelectionSource.MOUSE)
          // TODO: This should only be for 'selection'=inclusive
          moveCaretOneCharLeftFromSelectionEnd(editor, predictedMode)

          // Reset caret after forceBarShape while dragging
          editor.updateCaretsVisualAttributes()
          caret.vimLastColumn = editor.caretModel.visualPosition.column
        }

        mouseDragging = false
        cutOffFixed = false
      }
    }

    override fun mouseClicked(event: EditorMouseEvent) {
      if (event.editor.isIdeaVimDisabledHere) return
      logger.debug("Mouse clicked")

      if (event.area == EditorMouseEventArea.EDITING_AREA) {
        VimPlugin.getMotion()
        val editor = event.editor
        if (ExEntryPanel.getInstance().isActive) {
          VimPlugin.getProcess().cancelExEntry(editor, false)
        }

        ExOutputModel.getInstance(editor).clear()

        val caretModel = editor.caretModel
        if (editor.subMode != CommandState.SubMode.NONE) {
          caretModel.removeSecondaryCarets()
        }

        // Removing selection on just clicking.
        //
        // Actually, this event should not be fired on right click (when the menu appears), but since 202 it happens
        //   sometimes. To prevent unwanted selection removing, an assertion for isRightClick was added.
        // See:
        //   https://youtrack.jetbrains.com/issue/IDEA-277716
        //   https://youtrack.jetbrains.com/issue/VIM-2368
        if (event.mouseEvent.clickCount == 1 && !SwingUtilities.isRightMouseButton(event.mouseEvent)) {
          if (editor.inVisualMode) {
            editor.exitVisualMode()
          } else if (editor.inSelectMode) {
            editor.exitSelectMode(false)
            KeyHandler.getInstance().reset(editor.vim)
          }
        }

        // TODO: 2019-03-22 Multi?
        caretModel.primaryCaret.vimLastColumn = caretModel.visualPosition.column
      } else if (event.area != EditorMouseEventArea.ANNOTATIONS_AREA &&
        event.area != EditorMouseEventArea.FOLDING_OUTLINE_AREA &&
        event.mouseEvent.button != MouseEvent.BUTTON3
      ) {
        VimPlugin.getMotion()
        if (ExEntryPanel.getInstance().isActive) {
          VimPlugin.getProcess().cancelExEntry(event.editor, false)
        }

        ExOutputModel.getInstance(event.editor).clear()
      }
    }
  }

  private object ComponentMouseListener : MouseAdapter() {

    var cutOffEnd = false

    override fun mousePressed(e: MouseEvent?) {
      val editor = (e?.component as? EditorComponentImpl)?.editor ?: return
      if (editor.isIdeaVimDisabledHere) return
      val predictedMode = IdeaSelectionControl.predictMode(editor, SelectionSource.MOUSE)
      when (e.clickCount) {
        1 -> {
          // If you click after the line, the caret is placed by IJ after the last symbol.
          // This is not allowed in some vim modes, so we move the caret over the last symbol.
          if (!predictedMode.isEndAllowed) {
            @Suppress("ideavimRunForEachCaret")
            editor.caretModel.runForEachCaret { caret ->
              val lineEnd = EditorHelper.getLineEndForOffset(editor, caret.offset)
              val lineStart = EditorHelper.getLineStartForOffset(editor, caret.offset)
              cutOffEnd = if (caret.offset == lineEnd && lineEnd != lineStart) {
                caret.moveToInlayAwareOffset(caret.offset - 1)
                true
              } else {
                false
              }
            }
          } else cutOffEnd = false
        }
        // Double-clicking a word in IntelliJ will select the word and locate the caret at the end of the selection,
        // on the following character. When using a bar caret, this is drawn as between the end of selection and the
        // following char. With a block caret, this draws the caret "over" the following character.
        // In Vim, when 'selection' is "inclusive" (default), double clicking a word will select the last character of
        // the word and leave the caret on the last character, drawn as a block caret. We move one character left to
        // match this behaviour.
        // When 'selection' is exclusive, the caret is placed *after* the end of the word, and is drawn using the 've'
        // option of 'guicursor' - as a bar, so it appears to be in between the end of the word and the start of the
        // following character.
        // TODO: Modify this to support 'selection' set to "exclusive"
        2 -> moveCaretOneCharLeftFromSelectionEnd(editor, predictedMode)
      }
    }
  }

  enum class SelectionSource {
    MOUSE,
    OTHER
  }
}

private object MouseEventsDataHolder {
  const val skipEvents = 3
  var skipNDragEvents = skipEvents
}
