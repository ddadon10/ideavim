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

package org.jetbrains.plugins.ideavim.ex.implementation.commands

import com.intellij.testFramework.PlatformTestUtil
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.ex.vimscript.VimScriptGlobalEnvironment
import com.maddyhome.idea.vim.helper.StringHelper.parseKeys
import com.maddyhome.idea.vim.vimscript.services.OptionConstants
import com.maddyhome.idea.vim.vimscript.services.OptionService
import org.jetbrains.plugins.ideavim.SkipNeovimReason
import org.jetbrains.plugins.ideavim.TestWithoutNeovim
import org.jetbrains.plugins.ideavim.VimTestCase

class LetCommandTest : VimTestCase() {

  fun `test assignment to string`() {
    configureByText("\n")
    typeText(commandToKeys("let s = \"foo\""))
    typeText(commandToKeys("echo s"))
    assertExOutput("foo\n")
  }

  fun `test assignment to number`() {
    configureByText("\n")
    typeText(commandToKeys("let s = 100"))
    typeText(commandToKeys("echo s"))
    assertExOutput("100\n")
  }

  fun `test assignment to expression`() {
    configureByText("\n")
    typeText(commandToKeys("let s = 10 + 20 * 4"))
    typeText(commandToKeys("echo s"))
    assertExOutput("90\n")
  }

  fun `test adding new pair to dictionary`() {
    configureByText("\n")
    typeText(commandToKeys("let s = {'key1' : 1}"))
    typeText(commandToKeys("let s['key2'] = 2"))
    typeText(commandToKeys("echo s"))
    assertExOutput("{'key1': 1, 'key2': 2}\n")
  }

  fun `test editing existing pair in dictionary`() {
    configureByText("\n")
    typeText(commandToKeys("let s = {'key1' : 1}"))
    typeText(commandToKeys("let s['key1'] = 2"))
    typeText(commandToKeys("echo s"))
    assertExOutput("{'key1': 2}\n")
  }

  fun `test assignment plus operator`() {
    configureByText("\n")
    typeText(commandToKeys("let s = 10"))
    typeText(commandToKeys("let s += 5"))
    typeText(commandToKeys("echo s"))
    assertExOutput("15\n")
  }

  fun `test changing list item`() {
    configureByText("\n")
    typeText(commandToKeys("let s = [1, 1]"))
    typeText(commandToKeys("let s[1] = 2"))
    typeText(commandToKeys("echo s"))
    assertExOutput("[1, 2]\n")
  }

  @TestWithoutNeovim(reason = SkipNeovimReason.PLUGIN_ERROR)
  fun `test changing list item with index out of range`() {
    configureByText("\n")
    typeText(commandToKeys("let s = [1, 1]"))
    typeText(commandToKeys("let s[2] = 2"))
    assertPluginError(true)
    assertPluginErrorMessageContains("E684: list index out of range: 2")
  }

  fun `test changing list with sublist expression`() {
    configureByText("\n")
    typeText(commandToKeys("let s = [1, 2, 3]"))
    typeText(commandToKeys("let s[0:1] = [5, 4]"))
    typeText(commandToKeys("echo s"))
    assertExOutput("[5, 4, 3]\n")
  }

  @TestWithoutNeovim(reason = SkipNeovimReason.PLUGIN_ERROR)
  fun `test changing list with sublist expression and larger list`() {
    configureByText("\n")
    typeText(commandToKeys("let s = [1, 2, 3]"))
    typeText(commandToKeys("let s[0:1] = [5, 4, 3, 2, 1]"))
    assertPluginError(true)
    assertPluginErrorMessageContains("E710: List value has more items than targets")
  }

  @TestWithoutNeovim(reason = SkipNeovimReason.PLUGIN_ERROR)
  fun `test changing list with sublist expression and smaller list`() {
    configureByText("\n")
    typeText(commandToKeys("let s = [1, 2, 3]"))
    typeText(commandToKeys("let s[0:1] = [5]"))
    assertPluginError(true)
    assertPluginErrorMessageContains("E711: List value does not have enough items")
  }

  fun `test changing list with sublist expression and undefined end`() {
    configureByText("\n")
    typeText(commandToKeys("let s = [1, 2, 3]"))
    typeText(commandToKeys("let s[1:] = [5, 5, 5, 5]"))
    typeText(commandToKeys("echo s"))
    assertExOutput("[1, 5, 5, 5, 5]\n")
  }

  fun `test let option`() {
    configureByText("\n")
    typeText(commandToKeys("set noincsearch"))
    assertFalse(VimPlugin.getOptionService().isSet(OptionService.Scope.GLOBAL, OptionConstants.incsearchName))
    typeText(commandToKeys("let &incsearch = 12"))
    assertTrue(VimPlugin.getOptionService().isSet(OptionService.Scope.GLOBAL, OptionConstants.incsearchName))
    typeText(commandToKeys("set noincsearch"))
    assertFalse(VimPlugin.getOptionService().isSet(OptionService.Scope.GLOBAL, OptionConstants.incsearchName))
  }

  fun `test let option2`() {
    configureByText("\n")
    typeText(commandToKeys("set incsearch"))
    assertTrue(VimPlugin.getOptionService().isSet(OptionService.Scope.GLOBAL, OptionConstants.incsearchName))
    typeText(commandToKeys("let &incsearch = 0"))
    assertFalse(VimPlugin.getOptionService().isSet(OptionService.Scope.GLOBAL, OptionConstants.incsearchName))
  }

  fun `test comment`() {
    configureByText("\n")
    typeText(commandToKeys("let s = [1, 2, 3] \" my list for storing numbers"))
    typeText(commandToKeys("echo s"))
    assertExOutput("[1, 2, 3]\n")
  }

  fun `test vimScriptGlobalEnvironment`() {
    configureByText("\n")
    typeText(commandToKeys("let g:WhichKey_ShowVimActions = \"true\""))
    typeText(commandToKeys("echo g:WhichKey_ShowVimActions"))
    assertExOutput("true\n")
    assertEquals("true", VimScriptGlobalEnvironment.getInstance().variables["g:WhichKey_ShowVimActions"])
  }

  fun `test list is passed by reference`() {
    configureByText("\n")
    typeText(commandToKeys("let list = [1, 2, 3]"))
    typeText(commandToKeys("let l2 = list"))
    typeText(commandToKeys("let list += [4]"))
    typeText(commandToKeys("echo l2"))

    assertExOutput("[1, 2, 3, 4]\n")
  }

  fun `test list is passed by reference 2`() {
    configureByText("\n")
    typeText(commandToKeys("let list = [1, 2, 3, []]"))
    typeText(commandToKeys("let l2 = list"))
    typeText(commandToKeys("let list[3] += [4]"))
    typeText(commandToKeys("echo l2"))

    assertExOutput("[1, 2, 3, [4]]\n")
  }

  fun `test list is passed by reference 3`() {
    configureByText("\n")
    typeText(commandToKeys("let list = [1, 2, 3, []]"))
    typeText(commandToKeys("let dict = {}"))
    typeText(commandToKeys("let dict.l2 = list"))
    typeText(commandToKeys("let list[3] += [4]"))
    typeText(commandToKeys("echo dict.l2"))

    assertExOutput("[1, 2, 3, [4]]\n")
  }

  fun `test list is passed by reference 4`() {
    configureByText("\n")
    typeText(commandToKeys("let list = [1, 2, 3]"))
    typeText(commandToKeys("let dict = {}"))
    typeText(commandToKeys("let dict.l2 = list"))
    typeText(commandToKeys("let dict.l2 += [4]"))
    typeText(commandToKeys("echo dict.l2"))

    assertExOutput("[1, 2, 3, 4]\n")
  }

  fun `test number is passed by value`() {
    configureByText("\n")
    typeText(commandToKeys("let number = 10"))
    typeText(commandToKeys("let n2 = number"))
    typeText(commandToKeys("let number += 2"))
    typeText(commandToKeys("echo n2"))

    assertExOutput("10\n")
  }

  fun `test string is passed by value`() {
    configureByText("\n")
    typeText(commandToKeys("let string = 'abc'"))
    typeText(commandToKeys("let str2 = string"))
    typeText(commandToKeys("let string .= 'd'"))
    typeText(commandToKeys("echo str2"))

    assertExOutput("abc\n")
  }

  fun `test dict is passed by reference`() {
    configureByText("\n")
    typeText(commandToKeys("let dictionary = {}"))
    typeText(commandToKeys("let dict2 = dictionary"))
    typeText(commandToKeys("let dictionary.one = 1"))
    typeText(commandToKeys("let dictionary['two'] = 2"))
    typeText(commandToKeys("echo dict2"))

    assertExOutput("{'one': 1, 'two': 2}\n")
  }

  fun `test dict is passed by reference 2`() {
    configureByText("\n")
    typeText(commandToKeys("let list = [1, 2, 3, {'a': 'b'}]"))
    typeText(commandToKeys("let dict = list[3]"))
    typeText(commandToKeys("let list[3].key = 'value'"))
    typeText(commandToKeys("echo dict"))

    assertExOutput("{'a': 'b', 'key': 'value'}\n")
  }

  fun `test numbered register`() {
    configureByText("\n")
    typeText(commandToKeys("let @4 = 'inumber register works'"))
    typeText(commandToKeys("echo @4"))
    assertExOutput("inumber register works\n")

    typeText(parseKeys("@4"))
    if (VimPlugin.getOptionService().isSet(OptionService.Scope.GLOBAL, OptionConstants.ideadelaymacroName)) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
    assertState("number register works\n")
  }

  fun `test lowercase letter register`() {
    configureByText("\n")
    typeText(commandToKeys("let @o = 'ilowercase letter register works'"))
    typeText(commandToKeys("echo @o"))
    assertExOutput("ilowercase letter register works\n")

    typeText(parseKeys("@o"))
    if (VimPlugin.getOptionService().isSet(OptionService.Scope.GLOBAL, OptionConstants.ideadelaymacroName)) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
    assertState("lowercase letter register works\n")
  }

  fun `test uppercase letter register`() {
    configureByText("\n")
    typeText(commandToKeys("let @O = 'iuppercase letter register works'"))
    typeText(commandToKeys("echo @O"))
    assertExOutput("iuppercase letter register works\n")

    typeText(parseKeys("@O"))
    if (VimPlugin.getOptionService().isSet(OptionService.Scope.GLOBAL, OptionConstants.ideadelaymacroName)) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
    assertState("uppercase letter register works\n")
    typeText(parseKeys("<Esc>"))

    typeText(commandToKeys("let @O = '!'"))
    typeText(commandToKeys("echo @O"))
    assertExOutput("iuppercase letter register works!\n")
  }

  fun `test unnamed register`() {
    configureByText("\n")
    typeText(commandToKeys("let @\" = 'iunnamed register works'"))
    typeText(commandToKeys("echo @\""))
    assertExOutput("iunnamed register works\n")

    typeText(parseKeys("@\""))
    if (VimPlugin.getOptionService().isSet(OptionService.Scope.GLOBAL, OptionConstants.ideadelaymacroName)) {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }
    assertState("unnamed register works\n")
  }

  @TestWithoutNeovim(reason = SkipNeovimReason.PLUGIN_ERROR)
  fun `test define script variable with command line context`() {
    configureByText("\n")
    typeText(commandToKeys("let s:my_var = 'oh, hi Mark'"))
    assertPluginError(true)
    assertPluginErrorMessageContains("E461: Illegal variable name: s:my_var")
  }

  @TestWithoutNeovim(reason = SkipNeovimReason.PLUGIN_ERROR)
  fun `test define local variable with command line context`() {
    configureByText("\n")
    typeText(commandToKeys("let l:my_var = 'oh, hi Mark'"))
    assertPluginError(true)
    assertPluginErrorMessageContains("E461: Illegal variable name: l:my_var")
  }

  @TestWithoutNeovim(reason = SkipNeovimReason.PLUGIN_ERROR)
  fun `test define function variable with command line context`() {
    configureByText("\n")
    typeText(commandToKeys("let a:my_var = 'oh, hi Mark'"))
    assertPluginError(true)
    assertPluginErrorMessageContains("E461: Illegal variable name: a:my_var")
  }
}
