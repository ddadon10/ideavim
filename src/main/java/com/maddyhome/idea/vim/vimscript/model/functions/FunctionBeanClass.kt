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

package com.maddyhome.idea.vim.vimscript.model.functions

import com.intellij.openapi.diagnostic.logger
import com.intellij.serviceContainer.BaseKeyedLazyInstance
import com.intellij.util.xmlb.annotations.Attribute
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.vimscript.services.FunctionStorage

class FunctionBeanClass : BaseKeyedLazyInstance<FunctionHandler>() {

  @Attribute("implementation")
  var implementation: String? = null

  @Attribute("name")
  var name: String? = null

  override fun getImplementationClassName(): String? = implementation

  fun register() {
    if (this.pluginDescriptor.pluginId != VimPlugin.getPluginId()) {
      logger<FunctionHandler>().error("IdeaVim doesn't accept contributions to `vimActions` extension points. Please create a plugin using `VimExtension`. Plugin to blame: ${this.pluginDescriptor.pluginId}")
      return
    }
    FunctionStorage.addHandler(this)
  }
}
