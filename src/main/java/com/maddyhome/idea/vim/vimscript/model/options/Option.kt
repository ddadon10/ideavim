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

package com.maddyhome.idea.vim.vimscript.model.options

import com.intellij.util.containers.ContainerUtil
import com.maddyhome.idea.vim.ex.ExException
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimDataType
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import com.maddyhome.idea.vim.vimscript.model.datatypes.parseNumber
import com.maddyhome.idea.vim.vimscript.services.OptionService

sealed class Option<T : VimDataType>(val name: String, val abbrev: String, private val defaultValue: T) {

  open fun getDefaultValue(): T {
    return defaultValue
  }

  private val listeners = ContainerUtil.createLockFreeCopyOnWriteList<OptionChangeListener<VimDataType>>()

  open fun addOptionChangeListener(listener: OptionChangeListener<VimDataType>) {
    listeners.add(listener)
  }

  open fun removeOptionChangeListener(listener: OptionChangeListener<VimDataType>) {
    listeners.remove(listener)
  }

  fun onChanged(scope: OptionService.Scope, oldValue: VimDataType) {
    for (listener in listeners) {
      when (scope) {
        is OptionService.Scope.GLOBAL -> listener.processGlobalValueChange(oldValue)
        is OptionService.Scope.LOCAL -> {
          if (listener is LocalOptionChangeListener) {
            listener.processLocalValueChange(oldValue, scope.editor)
          } else {
            listener.processGlobalValueChange(oldValue)
          }
        }
      }
    }
  }

  // todo 1.9 should return Result with exceptions
  abstract fun checkIfValueValid(value: VimDataType, token: String)

  abstract fun getValueIfAppend(currentValue: VimDataType, value: String, token: String): T
  abstract fun getValueIfPrepend(currentValue: VimDataType, value: String, token: String): T
  abstract fun getValueIfRemove(currentValue: VimDataType, value: String, token: String): T
}

class ToggleOption(name: String, abbrev: String, defaultValue: VimInt) : Option<VimInt>(name, abbrev, defaultValue) {
  constructor(name: String, abbrev: String, defaultValue: Boolean) : this(name, abbrev, if (defaultValue) VimInt.ONE else VimInt.ZERO)

  override fun checkIfValueValid(value: VimDataType, token: String) {
    if (value !is VimInt) {
      throw ExException("E474: Invalid argument: $token")
    }
  }

  override fun getValueIfAppend(currentValue: VimDataType, value: String, token: String): VimInt {
    throw ExException("E474: Invalid argument: $token")
  }

  override fun getValueIfPrepend(currentValue: VimDataType, value: String, token: String): VimInt {
    throw ExException("E474: Invalid argument: $token")
  }

  override fun getValueIfRemove(currentValue: VimDataType, value: String, token: String): VimInt {
    throw ExException("E474: Invalid argument: $token")
  }
}

open class NumberOption(name: String, abbrev: String, defaultValue: VimInt) : Option<VimInt>(name, abbrev, defaultValue) {
  constructor(name: String, abbrev: String, defaultValue: Int) : this(name, abbrev, VimInt(defaultValue))

  override fun checkIfValueValid(value: VimDataType, token: String) {
    if (value !is VimInt) {
      throw ExException("E521: Number required after =: $token")
    }
  }

  override fun getValueIfAppend(currentValue: VimDataType, value: String, token: String): VimInt {
    val valueToAdd = parseNumber(token) ?: throw ExException("E474: Invalid argument: $token")
    return VimInt((currentValue as VimInt).value + valueToAdd)
  }

  override fun getValueIfPrepend(currentValue: VimDataType, value: String, token: String): VimInt {
    val valueToAdd = parseNumber(token) ?: throw ExException("E474: Invalid argument: $token")
    return VimInt((currentValue as VimInt).value * valueToAdd)
  }

  override fun getValueIfRemove(currentValue: VimDataType, value: String, token: String): VimInt {
    val valueToAdd = parseNumber(token) ?: throw ExException("E474: Invalid argument: $token")
    return VimInt((currentValue as VimInt).value - valueToAdd)
  }
}

open class StringOption(name: String, abbrev: String, defaultValue: VimString, private val isList: Boolean = false, private val boundedValues: Collection<String>? = null) : Option<VimString>(name, abbrev, defaultValue) {
  constructor(name: String, abbrev: String, defaultValue: String, isList: Boolean = false, boundedValues: Collection<String>? = null) : this(name, abbrev, VimString(defaultValue), isList, boundedValues)

  override fun checkIfValueValid(value: VimDataType, token: String) {
    if (value !is VimString) {
      throw ExException("E474: Invalid argument: $token")
    }

    if (value.value.isEmpty()) {
      return
    }

    if (boundedValues != null && split(value.value)!!.any { !boundedValues.contains(it) }) {
      throw ExException("E474: Invalid argument: $token")
    }
  }

  override fun getValueIfAppend(currentValue: VimDataType, value: String, token: String): VimString {
    val separator = if (isList) "," else ""
    val newValue = (currentValue as VimString).value + separator + value
    return VimString(newValue)
  }

  override fun getValueIfPrepend(currentValue: VimDataType, value: String, token: String): VimString {
    val separator = if (isList) "," else ""
    val newValue = value + separator + (currentValue as VimString).value
    return VimString(newValue)
  }

  override fun getValueIfRemove(currentValue: VimDataType, value: String, token: String): VimString {
    val currentValueAsString = (currentValue as VimString).value
    val newValue = if (isList) {
      val elements = split(currentValueAsString)!!.toMutableList()
      elements.remove(value)
      elements.joinToString(separator = ",")
    } else {
      currentValueAsString.replace(value, "")
    }
    return VimString(newValue)
  }

  open fun split(value: String): List<String>? {
    return if (isList) {
      value.split(",")
    } else {
      listOf(value)
    }
  }
}
