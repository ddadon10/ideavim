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

package org.jetbrains.plugins.ideavim.action.copy

import com.intellij.codeInsight.editorActions.TextBlockTransferable
import com.intellij.openapi.ide.CopyPasteManager
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.command.SelectionType
import com.maddyhome.idea.vim.helper.StringHelper
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.services.OptionConstants
import com.maddyhome.idea.vim.vimscript.services.OptionService
import org.jetbrains.plugins.ideavim.SkipNeovimReason
import org.jetbrains.plugins.ideavim.TestWithoutNeovim
import org.jetbrains.plugins.ideavim.VimTestCase
import org.jetbrains.plugins.ideavim.rangeOf
import java.util.*

/**
 * @author Alex Plate
 */
class PutViaIdeaTest : VimTestCase() {

  private var optionsBefore: String = ""

  override fun setUp() {
    super.setUp()
    optionsBefore = (VimPlugin.getOptionService().getOptionValue(OptionService.Scope.GLOBAL, OptionConstants.clipboardName) as VimString).value
    VimPlugin.getOptionService().setOptionValue(OptionService.Scope.GLOBAL, OptionConstants.clipboardName, VimString("ideaput"))
  }

  override fun tearDown() {
    VimPlugin.getOptionService().setOptionValue(OptionService.Scope.GLOBAL, OptionConstants.clipboardName, VimString(optionsBefore))
    super.tearDown()
  }

  @TestWithoutNeovim(SkipNeovimReason.DIFFERENT)
  fun `test simple insert via idea`() {
    val before = "${c}I found it in a legendary land"
    configureByText(before)

    VimPlugin.getRegister()
      .storeText(myFixture.editor, before rangeOf "legendary", SelectionType.CHARACTER_WISE, false)

    typeText(StringHelper.parseKeys("ve", "p"))
    val after = "legendar${c}y it in a legendary land"
    assertState(after)
  }

  @TestWithoutNeovim(SkipNeovimReason.DIFFERENT)
  fun `test insert several times`() {
    val before = "${c}I found it in a legendary land"
    configureByText(before)

    VimPlugin.getRegister()
      .storeText(myFixture.editor, before rangeOf "legendary", SelectionType.CHARACTER_WISE, false)

    typeText(StringHelper.parseKeys("ppp"))
    val after = "Ilegendarylegendarylegendar${c}y found it in a legendary land"
    assertState(after)
  }

  fun `test insert doesn't clear existing elements`() {
    val randomUUID = UUID.randomUUID()
    val before = "${c}I found it in a legendary$randomUUID land"
    configureByText(before)

    CopyPasteManager.getInstance().setContents(TextBlockTransferable("Fill", emptyList(), null))
    CopyPasteManager.getInstance().setContents(TextBlockTransferable("Buffer", emptyList(), null))

    VimPlugin.getRegister()
      .storeText(myFixture.editor, before rangeOf "legendary$randomUUID", SelectionType.CHARACTER_WISE, false)

    val sizeBefore = CopyPasteManager.getInstance().allContents.size
    typeText(StringHelper.parseKeys("ve", "p"))
    assertEquals(sizeBefore, CopyPasteManager.getInstance().allContents.size)
  }

  @TestWithoutNeovim(SkipNeovimReason.DIFFERENT)
  fun `test insert block with newline`() {
    val before = """
            A Discovery
            $c
            I found it in a legendary land
            
            hard by the torrent of a mountain pass.
    """.trimIndent()
    configureByText(before)

    VimPlugin.getRegister().storeText(
      myFixture.editor,
      before rangeOf "\nI found it in a legendary land\n",
      SelectionType.CHARACTER_WISE,
      false
    )

    typeText(StringHelper.parseKeys("p"))
    val after = """
            A Discovery
            
            I found it in a legendary land
            
            I found it in a legendary land
            
            hard by the torrent of a mountain pass.
    """.trimIndent()
    assertState(after)
  }
}
