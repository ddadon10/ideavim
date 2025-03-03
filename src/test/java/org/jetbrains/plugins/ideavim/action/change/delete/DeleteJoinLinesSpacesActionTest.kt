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

package org.jetbrains.plugins.ideavim.action.change.delete

import com.maddyhome.idea.vim.command.CommandState
import com.maddyhome.idea.vim.vimscript.services.OptionConstants
import org.jetbrains.plugins.ideavim.OptionValueType
import org.jetbrains.plugins.ideavim.SkipNeovimReason
import org.jetbrains.plugins.ideavim.TestWithoutNeovim
import org.jetbrains.plugins.ideavim.VimOptionTestCase
import org.jetbrains.plugins.ideavim.VimOptionTestConfiguration
import org.jetbrains.plugins.ideavim.VimTestOption

class DeleteJoinLinesSpacesActionTest : VimOptionTestCase(OptionConstants.ideajoinName) {
  @VimOptionTestConfiguration(VimTestOption(OptionConstants.ideajoinName, OptionValueType.NUMBER, "1"))
  fun `test join with idea`() {
    doTest(
      "J",
      """
                A Discovery

                ${c}I found it in a legendary land
                all rocks and lavender and tufted grass,
                where it was settled on some sodden sand
                hard by the torrent of a mountain pass.
      """.trimIndent(),
      """
                A Discovery

                I found it in a legendary land$c all rocks and lavender and tufted grass,
                where it was settled on some sodden sand
                hard by the torrent of a mountain pass.
      """.trimIndent(),
      CommandState.Mode.COMMAND,
      CommandState.SubMode.NONE
    )
  }

  @VimOptionTestConfiguration(VimTestOption(OptionConstants.ideajoinName, OptionValueType.NUMBER, "1"))
  fun `test join with idea with count`() {
    doTest(
      "3J",
      """
                A Discovery

                ${c}I found it in a legendary land
                all rocks and lavender and tufted grass,
                where it was settled on some sodden sand
                hard by the torrent of a mountain pass.
      """.trimIndent(),
      """
                A Discovery

                I found it in a legendary land all rocks and lavender and tufted grass,$c where it was settled on some sodden sand
                hard by the torrent of a mountain pass.
      """.trimIndent(),
      CommandState.Mode.COMMAND,
      CommandState.SubMode.NONE
    )
  }

  @TestWithoutNeovim(SkipNeovimReason.OPTION)
  @VimOptionTestConfiguration(VimTestOption(OptionConstants.ideajoinName, OptionValueType.NUMBER, "1"))
  fun `test join with idea with large count`() {
    doTest(
      "10J",
      """
                A Discovery

                ${c}I found it in a legendary land
                all rocks and lavender and tufted grass,
                where it was settled on some sodden sand
                hard by the torrent of a mountain pass.
      """.trimIndent(),
      """
                A Discovery

                ${c}I found it in a legendary land
                all rocks and lavender and tufted grass,
                where it was settled on some sodden sand
                hard by the torrent of a mountain pass.
      """.trimIndent(),
      CommandState.Mode.COMMAND,
      CommandState.SubMode.NONE
    )
  }
}
