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
package org.jetbrains.plugins.ideavim.action

import com.intellij.testFramework.PlatformTestUtil
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.command.CommandState.Companion.getInstance
import com.maddyhome.idea.vim.helper.StringHelper
import com.maddyhome.idea.vim.helper.StringHelper.parseKeys
import com.maddyhome.idea.vim.vimscript.services.OptionConstants
import com.maddyhome.idea.vim.vimscript.services.OptionService
import junit.framework.TestCase
import org.jetbrains.plugins.ideavim.VimTestCase
import org.jetbrains.plugins.ideavim.rangeOf
import org.jetbrains.plugins.ideavim.waitAndAssert

/**
 * @author vlan
 */
class MacroActionTest : VimTestCase() {
  // |q|
  fun testRecordMacro() {
    val editor = typeTextInFile(parseKeys("qa", "3l", "q"), "on<caret>e two three\n")
    val commandState = getInstance(editor)
    assertFalse(commandState.isRecording)
    val registerGroup = VimPlugin.getRegister()
    val register = registerGroup.getRegister('a')
    assertNotNull(register)
    assertEquals("3l", register!!.text)
  }

  fun testRecordMacroDoesNotExpandMap() {
    configureByText("")
    enterCommand("imap pp hello")
    typeText(parseKeys("qa", "i", "pp<Esc>", "q"))
    val register = VimPlugin.getRegister().getRegister('a')
    assertNotNull(register)
    assertEquals("ipp<Esc>", StringHelper.toKeyNotation(register!!.keys))
  }

  fun testRecordMacroWithDigraph() {
    typeTextInFile(parseKeys("qa", "i", "<C-K>OK<Esc>", "q"), "")
    val register = VimPlugin.getRegister().getRegister('a')
    assertNotNull(register)
    assertEquals("i<C-K>OK<Esc>", StringHelper.toKeyNotation(register!!.keys))
  }

  fun `test macro with search`() {
    val content = """
            A Discovery

            ${c}I found it in a legendary land
            all rocks and lavender and tufted grass,
            where it was settled on some sodden sand
            hard by the torrent of a mountain pass.
    """.trimIndent()
    configureByText(content)
    typeText(parseKeys("qa", "/rocks<CR>", "q", "gg", "@a"))

    val startOffset = content.rangeOf("rocks").startOffset

    waitAndAssert {
      startOffset == myFixture.editor.caretModel.offset
    }
  }

  fun `test macro with command`() {
    val content = """
            A Discovery

            ${c}I found it in a legendary land
            all rocks and lavender and tufted grass,
            where it was settled on some sodden sand
            hard by the torrent of a mountain pass.
    """.trimIndent()
    configureByText(content)
    typeText(parseKeys("qa", ":map x y<CR>", "q"))

    val register = VimPlugin.getRegister().getRegister('a')
    val registerSize = register!!.keys.size
    TestCase.assertEquals(9, registerSize)
  }

  fun `test last command`() {
    val content = "${c}0\n1\n2\n3\n"
    configureByText(content)
    typeText(parseKeys(":d<CR>", "@:"))
    assertState("2\n3\n")
  }

  fun `test last command with count`() {
    val content = "${c}0\n1\n2\n3\n4\n5\n"
    configureByText(content)
    typeText(parseKeys(":d<CR>", "4@:"))
    assertState("5\n")
  }

  fun `test last command as last macro with count`() {
    val content = "${c}0\n1\n2\n3\n4\n5\n"
    configureByText(content)
    typeText(parseKeys(":d<CR>", "@:", "3@@"))
    assertState("5\n")
  }

  // Broken, see the resulting text
  fun `ignore test macro with macro`() {
    val content = """
            A Discovery

            ${c}I found it in a legendary land
            all rocks and lavender and tufted grass,
            where it was settled on some sodden sand
            hard by the torrent of a mountain pass.
    """.trimIndent()
    configureByText(content)
    typeText(parseKeys("qa", "l", "q", "qb", "10@a", "q", "2@b"))

    val startOffset = content.rangeOf("rocks").startOffset

    waitAndAssert {
      println(myFixture.editor.caretModel.offset)
      println(startOffset)
      println()
      startOffset == myFixture.editor.caretModel.offset
    }
  }

  fun `test macro with count`() {
    configureByText("${c}0\n1\n2\n3\n4\n5\n")
    typeText(parseKeys("qajq", "4@a"))
    if (VimPlugin.getOptionService().isSet(OptionService.Scope.GLOBAL, OptionConstants.ideadelaymacroName)) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
    assertState("0\n1\n2\n3\n4\n${c}5\n")
  }
}
