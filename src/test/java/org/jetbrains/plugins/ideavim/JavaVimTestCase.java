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

package org.jetbrains.plugins.ideavim;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import com.maddyhome.idea.vim.KeyHandler;
import com.maddyhome.idea.vim.VimPlugin;
import com.maddyhome.idea.vim.command.CommandState;
import com.maddyhome.idea.vim.group.visual.VimVisualTimer;
import com.maddyhome.idea.vim.helper.EditorDataContext;
import com.maddyhome.idea.vim.helper.RunnableHelper;
import com.maddyhome.idea.vim.helper.TestInputModel;
import com.maddyhome.idea.vim.newapi.IjExecutionContext;
import com.maddyhome.idea.vim.newapi.IjVimEditor;
import com.maddyhome.idea.vim.ui.ex.ExEntryPanel;
import com.maddyhome.idea.vim.vimscript.services.OptionService;
import com.maddyhome.idea.vim.vimscript.services.VariableServiceImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;

/**
 * NB: We need to extend from JavaCodeInsightFixtureTestCase so we
 * can create PsiFiles with proper Java Language type
 *
 * @author dhleong
 */
public abstract class JavaVimTestCase extends JavaCodeInsightFixtureTestCase {
  @Override
  protected void setUp() throws Exception {
    super.setUp();

    Editor editor = myFixture.getEditor();
    if (editor != null) {
      KeyHandler.getInstance().fullReset(new IjVimEditor(editor));
    }
    VimPlugin.getOptionService().resetAllOptions();
    VimPlugin.getKey().resetKeyMappings();
    VimPlugin.clearError();
  }

  @Override
  protected void tearDown() throws Exception {
    ExEntryPanel.getInstance().deactivate(false);
    ((VariableServiceImpl) VimPlugin.getVariableService()).clear();
    Timer swingTimer = VimVisualTimer.INSTANCE.getSwingTimer();
    if (swingTimer != null) {
      swingTimer.stop();
    }
    super.tearDown();
  }

  protected void enableExtensions(@NotNull String... extensionNames) {
    for (String name : extensionNames) {
      VimPlugin.getOptionService().setOption(OptionService.Scope.GLOBAL.INSTANCE, name,  name);
    }
  }

  public void doTest(final List<KeyStroke> keys, String before, String after) {
    //noinspection IdeaVimAssertState
    myFixture.configureByText(JavaFileType.INSTANCE, before);
    typeText(keys);
    //noinspection IdeaVimAssertState
    myFixture.checkResult(after);
  }

  @NotNull
  protected Editor typeText(@NotNull List<KeyStroke> keys) {
    final Editor editor = myFixture.getEditor();
    final KeyHandler keyHandler = KeyHandler.getInstance();
    final EditorDataContext dataContext = EditorDataContext.init(editor, null);
    final Project project = myFixture.getProject();
    TestInputModel.getInstance(editor).setKeyStrokes(keys);
    RunnableHelper.runWriteCommand(project, () -> {
      final TestInputModel inputModel = TestInputModel.getInstance(editor);
      for (KeyStroke key = inputModel.nextKeyStroke(); key != null; key = inputModel.nextKeyStroke()) {
        final ExEntryPanel exEntryPanel = ExEntryPanel.getInstance();
        if (exEntryPanel.isActive()) {
          exEntryPanel.handleKey(key);
        }
        else {
          keyHandler.handleKey(editor, key, new IjExecutionContext(dataContext));
        }
      }
    }, null, null);
    return editor;
  }

  public void assertMode(@NotNull CommandState.Mode expectedMode) {
    final CommandState.Mode mode = CommandState.getInstance(myFixture.getEditor()).getMode();
    assertEquals(expectedMode, mode);
  }

  public void assertSelection(@Nullable String expected) {
    final String selected = myFixture.getEditor().getSelectionModel().getSelectedText();
    assertEquals(expected, selected);
  }

}
