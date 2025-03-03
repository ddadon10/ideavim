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

package org.jetbrains.plugins.ideavim.action.motion.`object`

import com.maddyhome.idea.vim.command.CommandState
import com.maddyhome.idea.vim.helper.VimBehaviorDiffers
import org.jetbrains.plugins.ideavim.VimTestCase

class MotionOuterSentenceActionTest : VimTestCase() {
  @VimBehaviorDiffers(
    originalVimAfter = """
        I found it in a legendary land
        all rocks and lavender and tufted grass,
        where it was settled on some sodden sand
        $c
    """
  )
  fun `test on empty last line`() {
    doTest(
      "=as",
      """
        I found it in a legendary land
        all rocks and lavender and tufted grass,
        where it was settled on some sodden sand
        $c
      """.trimIndent(),
      """
        ${c}I found it in a legendary land
        all rocks and lavender and tufted grass,
        where it was settled on some sodden sand
        
      """.trimIndent(),
      CommandState.Mode.COMMAND, CommandState.SubMode.NONE
    )
  }

  @VimBehaviorDiffers(
    originalVimAfter = """
        I found it in a legendary land
        all rocks and lavender and tufted grass,
        where it was settled on some sodden sand
        $c
    """
  )
  fun `test delete on empty last line`() {
    doTest(
      "das",
      """
        I found it in a legendary land
        all rocks and lavender and tufted grass,
        where it was settled on some sodden sand
        $c
      """.trimIndent(),
      "\n",
      CommandState.Mode.COMMAND, CommandState.SubMode.NONE
    )
  }
}
