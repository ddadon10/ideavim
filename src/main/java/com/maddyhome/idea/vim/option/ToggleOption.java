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

package com.maddyhome.idea.vim.option;

import com.maddyhome.idea.vim.VimPlugin;
import com.maddyhome.idea.vim.helper.VimNlsSafe;
import com.maddyhome.idea.vim.vimscript.model.commands.SetCommand;
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt;
import com.maddyhome.idea.vim.vimscript.services.OptionService;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a boolean option
 * @deprecated use {@link com.maddyhome.idea.vim.vimscript.model.options.Option} instead
 */
@Deprecated
@ApiStatus.ScheduledForRemoval(inVersion = "1.11")
public class ToggleOption extends Option<Boolean> {

  private static final @NonNls String NO_PREFIX = "no";
  protected final boolean dflt;
  protected boolean value;

  /**
   * Creates the option
   *
   * @param name   The option's name
   * @param abbrev The short name
   * @param dflt   The default value
   */
  public ToggleOption(@VimNlsSafe String name, @VimNlsSafe String abbrev, boolean dflt) {
    super(name, abbrev);

    this.dflt = dflt;
    this.value = dflt;
  }

  @Override
  public Boolean getValue() {
    return value;
  }

  /**
   * Sets the on (true)
   */
  public void set() {
    update(true);
  }

  /**
   * Resets the option (false)
   */
  public void reset() {
    update(false);
  }

  /**
   * Toggles the option's value (false to true, true to false)
   */
  public void toggle() {
    update(!value);
  }

  public boolean isSet() {
    return value;
  }

  /**
   * Helper to set the value only it is changing and notify listeners
   *
   * @param val The new value
   */
  private void update(boolean val) {
    boolean old = value;
    value = val;
    if (val != old) {
      onChanged(old, val);
    }
    // we won't use OptionService if the method was invoked during set command execution (set command will call OptionService by itself)
    if (!SetCommand.Companion.isExecutingCommand$IdeaVIM()) {
      try {
        if (VimPlugin.getOptionService().isSet(OptionService.Scope.GLOBAL.INSTANCE, name, name) != val) {
          VimPlugin.getOptionService().setOptionValue(OptionService.Scope.GLOBAL.INSTANCE, name, new VimInt(val ? 1 : 0), name);
        }
      }
      catch (Exception e) {
      }
    }
}

  /**
   * The display value of the option [no]{name}
   *
   * @return The option's display value
   */
  public @NotNull String toString() {
    StringBuilder res = new StringBuilder();
    if (!value) {
      res.append(NO_PREFIX);
    }
    else {
      res.append("  ");
    }

    res.append(getName());

    return res.toString();
  }

  /**
   * Checks to see if the option's current value equals the default value
   *
   * @return True if equal to default, false if not.
   */
  @Override
  public boolean isDefault() {
    return value == dflt;
  }

  /**
   * Sets the option to its default value.
   */
  @Override
  public void resetDefault() {
    value = dflt;
    try {
      if (VimPlugin.getOptionService().isSet(OptionService.Scope.GLOBAL.INSTANCE, name, name) != dflt) {
        VimPlugin.getOptionService().setOptionValue(OptionService.Scope.GLOBAL.INSTANCE, name, new VimInt(dflt ? 1 : 0), name);
      }
    } catch (Exception e) {}
  }
}
