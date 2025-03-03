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

package com.maddyhome.idea.vim.helper;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import com.maddyhome.idea.vim.VimPlugin;
import com.maddyhome.idea.vim.group.RegisterGroup;
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString;
import org.apache.commons.codec.binary.Base64;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import static java.awt.event.KeyEvent.*;
import static javax.swing.KeyStroke.getKeyStroke;

public class StringHelper {
  private static final String META_PREFIX = "m-";
  private static final String ALT_PREFIX = "a-";
  private static final String CTRL_PREFIX = "c-";
  private static final String SHIFT_PREFIX = "s-";

  /**
   * Fake key for <Plug> mappings
   */
  private static final int VK_PLUG = KeyEvent.CHAR_UNDEFINED - 1;
  public static final int VK_ACTION = KeyEvent.CHAR_UNDEFINED - 2;

  private static final Logger logger = Logger.getInstance(RegisterGroup.class);

  private StringHelper() {}

  private static @Nullable String toEscapeNotation(@NotNull KeyStroke key) {
    final char c = key.getKeyChar();
    if (isControlCharacter(c)) {
      return "^" + (char)(c + 'A' - 1);
    }
    else if (isControlKeyCode(key)) {
      return "^" + (char)(key.getKeyCode() + 'A' - 1);
    }
    return null;
  }

  public static @NotNull List<KeyStroke> stringToKeys(@NotNull @NonNls String s) {
    final List<KeyStroke> res = new ArrayList<>();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (isControlCharacter(c) && c != 10) {
        if (c == 0) {
          // J is a special case, it's keycode is 0 because keycode 10 is reserved by \n
          res.add(getKeyStroke('J', CTRL_DOWN_MASK));
        } else if (c == '\t') {
          res.add(getKeyStroke('\t'));
        } else {
          res.add(getKeyStroke(c + 'A' - 1, CTRL_DOWN_MASK));
        }
      } else {
        res.add(getKeyStroke(c));
      }
    }
    return res;
  }

  public static final @NotNull KeyStroke PlugKeyStroke = parseKeys("<Plug>").get(0);

  private enum KeyParserState {
    INIT,
    ESCAPE,
    SPECIAL,
  }

  /**
   * Parses Vim key notation strings.
   *
   * @throws java.lang.IllegalArgumentException if the mapping doesn't make sense for Vim emulation
   */
  public static @NotNull List<KeyStroke> parseKeys(@NotNull @NonNls String... strings) {
    final List<KeyStroke> result = new ArrayList<>();
    for (String s : strings) {
      KeyParserState state = KeyParserState.INIT;
      StringBuilder specialKeyBuilder = new StringBuilder();
      for (int i = 0; i < s.length(); i++) {
        final char c = s.charAt(i);
        switch (state) {
          case INIT:
            if (c == '\\') {
              state = KeyParserState.ESCAPE;
            }
            else if (c == '<' || c == '«') {
              state = KeyParserState.SPECIAL;
              specialKeyBuilder = new StringBuilder();
            }
            else {
              final KeyStroke stroke;
              if (c == '\t' || c == '\n') {
                stroke = getKeyStroke(c, 0);
              }
              else if (isControlCharacter(c)) {
                stroke = getKeyStroke(c + 'A' - 1, CTRL_DOWN_MASK);
              }
              else {
                stroke = getKeyStroke(c);
              }
              result.add(stroke);
            }
            break;
          case ESCAPE:
            state = KeyParserState.INIT;
            if (c != '\\') {
              result.add(getKeyStroke('\\'));
            }
            result.add(getKeyStroke(c));
            break;
          case SPECIAL:
            if (c == '>' || c == '»') {
              state = KeyParserState.INIT;
              final String specialKeyName = specialKeyBuilder.toString();
              final String lower = specialKeyName.toLowerCase();
              if ("sid".equals(lower)) {
                throw new IllegalArgumentException("<" + specialKeyName + "> is not supported");
              }
              if ("comma".equals(lower)) {
                result.add(KeyStroke.getKeyStroke(','));
              }
              else if (!"nop".equals(lower)) {
                final List<KeyStroke> leader = parseMapLeader(specialKeyName);
                final KeyStroke specialKey = parseSpecialKey(specialKeyName, 0);
                if (leader != null) {
                  result.addAll(leader);
                }
                else if (specialKey != null && specialKeyName.length() > 1) {
                  result.add(specialKey);
                }
                else {
                  result.add(getKeyStroke('<'));
                  result.addAll(stringToKeys(specialKeyName));
                  result.add(getKeyStroke('>'));
                }
              }
            }
            else {
              specialKeyBuilder.append(c);
            }
            break;
        }
      }
      if (state == KeyParserState.ESCAPE) {
        result.add(getKeyStroke('\\'));
      }
      else if (state == KeyParserState.SPECIAL) {
        result.add(getKeyStroke('<'));
        result.addAll(stringToKeys(specialKeyBuilder.toString()));
      }
    }
    return result;
  }

  /**
   * See ":h string"
   * \...   three-digit octal number
   * \..    two-digit octal number (must be followed by non-digit)
   * \.     one-digit octal number (must be followed by non-digit)
   * \x..   byte specified with two hex numbers (e.g., "\x1f")
   * \x.    byte specified with one hex number (must be followed by non-hex char)
   * \X..   same as \x..
   * \X.    same as \x.
   * \\u.... character specified with up to 4 hex numbers, stored as UTF-8
   * \U.... same as \\u but allows up to 8 hex numbers
   * \b     backspace
   * \e     escape
   * \f     formfeed
   * \n     newline
   * \r     return
   * \t     tab
   * \\     backslash
   * \"     double quote
   * \<xxx> special key named "xxx". e.g. "\<C-W>" for CTRL-W. This is for use in mapping, the 0x80 byte is escaped
   *        to use double quote character is must be escaped:  "<M-\">".
   *
   * Note that "\000" and "\x00" force end of the string (same for \\u, \U etc)
   *
   * @param vimString
   * @return
   */
  public static @NotNull String parseVimString(@NotNull String vimString) {
    StringBuilder result = new StringBuilder();
    VimStringState state = VimStringState.INIT;
    StringBuilder specialKeyBuilder = null;
    int digitsLeft = 0;
    int number = 0;
    String vimStringWithForceEnd = vimString + (char) 0;
    int i = 0;
    while (i < vimStringWithForceEnd.length()) {
      char c = vimStringWithForceEnd.charAt(i);
      switch (state) {
        case INIT:
          if (c == '\\') {
            state = VimStringState.ESCAPE;
          } else if (c == 0) {
            i = vimStringWithForceEnd.length();
          } else {
            result.append(c);
          }
          break;
        case ESCAPE:
          if (octalDigitToNumber(c) != null) {
            number = octalDigitToNumber(c);
            digitsLeft = 2;
            state = VimStringState.OCTAL_NUMBER;
          } else if (Character.toLowerCase(c) == 'x') {
            digitsLeft = 2;
            state = VimStringState.HEX_NUMBER;
          } else if (c == 'u') {
            digitsLeft = 4;
            state = VimStringState.HEX_NUMBER;
          } else if (c == 'U') {
            digitsLeft = 8;
            state = VimStringState.HEX_NUMBER;
          } else if (c == 'b')  {
            result.append((char) 8);
            state = VimStringState.INIT;
          } else if (c == 'e') {
            result.append((char) 27);
            state = VimStringState.INIT;
          } else if (c == 'f') {
            result.append((char) 12);
            state = VimStringState.INIT;
          } else if (c == 'n') {
            result.append('\n');
            state = VimStringState.INIT;
          } else if (c == 'r') {
            result.append('\r');
            state = VimStringState.INIT;
          } else if (c == 't') {
            result.append('\t');
            state = VimStringState.INIT;
          } else if (c == '\\') {
            result.append('\\');
            state = VimStringState.INIT;
          } else if (c == '"') {
            result.append('"');
            state = VimStringState.INIT;
          } else if (c == '<') {
            state = VimStringState.SPECIAL;
            specialKeyBuilder = new StringBuilder();
          } else if (c == 0) {
            i = vimStringWithForceEnd.length(); // force end of the string
          } else {
            result.append(c);
            state = VimStringState.INIT;
          }
          break;
        case OCTAL_NUMBER:
          Integer value = octalDigitToNumber(c);
          if (value != null) {
            digitsLeft -= 1;
            number = number * 8 + value;

            if (digitsLeft == 0 || i == vimStringWithForceEnd.length() - 1) {
              if (number != 0) {
                result.append((char)number);
              } else {
                i = vimStringWithForceEnd.length();
              }
              number = 0;
              state = VimStringState.INIT;
            }
          } else {
            if (number != 0) {
              result.append((char)number);
            } else {
              i = vimStringWithForceEnd.length();
            }
            number = 0;
            digitsLeft = 0;
            state = VimStringState.INIT;
            i -= 1;
          }
          break;
        case HEX_NUMBER:
          Integer val = hexDigitToNumber(c);
          if (val == null) {
            // if there was at least one number after '\', append number, otherwise - append letter after '\'
            if (vimStringWithForceEnd.charAt(i - 2) == '\\') {
              result.append(vimStringWithForceEnd.charAt(i - 1));
            } else {
              if (number != 0) {
                result.append((char)number);
              } else {
                i = vimStringWithForceEnd.length();
              }
            }
            number = 0;
            digitsLeft = 0;
            state = VimStringState.INIT;
            i -= 1;
          } else {
            number = number * 16 + val;
            digitsLeft -= 1;
            if (digitsLeft == 0 || i == vimStringWithForceEnd.length() - 1) {
              if (number != 0) {
                result.append((char)number);
              } else {
                i = vimStringWithForceEnd.length();
              }
              number = 0;
              state = VimStringState.INIT;
            }
          }
          break;
        case SPECIAL:
          if (c == 0) {
            result.append(specialKeyBuilder);
          }
          if (c == '>') {
            KeyStroke specialKey = parseSpecialKey(specialKeyBuilder.toString(), 0);
            if (specialKey != null) {
              int keyCode = specialKey.getKeyCode();
              if (specialKey.getKeyCode() == 0) {
                keyCode = specialKey.getKeyChar();
              } else if ((specialKey.getModifiers() & CTRL_DOWN_MASK) == CTRL_DOWN_MASK) {
                if (specialKey.getKeyCode() == 'J') {
                  // 'J' is a special case, keycode 10 is \n char
                  keyCode = 0;
                } else {
                  keyCode = specialKey.getKeyCode() - 'A' + 1;
                }
              }
              result.append((char) keyCode);
            } else {
              result.append("<").append(specialKeyBuilder).append(">");
            }
            specialKeyBuilder = new StringBuilder();
            state = VimStringState.INIT;
          } else if (c == 0) {
            result.append("<").append(specialKeyBuilder);
            state = VimStringState.INIT;
          } else {
            specialKeyBuilder.append(c);
          }
          break;
      }
      i += 1;
    }
    return result.toString();
  }

  private enum VimStringState {
    INIT,
    ESCAPE,
    OCTAL_NUMBER,
    HEX_NUMBER,
    SPECIAL
  }

  private static Integer octalDigitToNumber(char c) {
    if (c >= '0' && c <= '7') {
      return c - '0';
    }
    return null;
  }


  private static Integer hexDigitToNumber(char c) {
    char lowerChar = Character.toLowerCase(c);
    if (Character.isDigit(lowerChar)) {
      return lowerChar - '0';
    } else if (lowerChar >= 'a' && lowerChar <= 'f') {
      return lowerChar - 'a' + 10;
    }
    return null;
  }

  private static @Nullable List<KeyStroke> parseMapLeader(@NotNull String s) {
    if ("leader".equalsIgnoreCase(s)) {
      final Object mapLeader = VimPlugin.getVariableService().getGlobalVariableValue("mapleader");
      if (mapLeader instanceof VimString) {
        return stringToKeys(((VimString)mapLeader).getValue());
      }
      else {
        return stringToKeys("\\");
      }
    }
    return null;
  }

  private static boolean isControlCharacter(char c) {
    return c < '\u0020';
  }

  private static boolean isControlKeyCode(@NotNull KeyStroke key) {
    return key.getKeyChar() == CHAR_UNDEFINED && key.getKeyCode() < 0x20 && key.getModifiers() == 0;
  }

  public static @NotNull String toKeyCodedString(@NotNull List<KeyStroke> keys) {
    final StringBuilder builder = new StringBuilder();
    for (KeyStroke key : keys) {
      Character keyAsChar = keyStrokeToChar(key);
      if (keyAsChar != null) {
        builder.append(keyAsChar);
      } else {
        logger.error("Unknown key " + key);
      }
    }
    return builder.toString();
  }

  private static Character keyStrokeToChar(@NotNull KeyStroke key) {
    if (key.getKeyChar() != CHAR_UNDEFINED) {
      return key.getKeyChar();
    } else if ((key.getModifiers() & CTRL_DOWN_MASK) == CTRL_DOWN_MASK) {
      if (key.getKeyCode() == 'J') {
        // 'J' is a special case, keycode 10 is \n char
        return (char)0;
      }
      else {
        return (char)(key.getKeyCode() - 'A' + 1);
      }
    }
    return (char) key.getKeyCode();
  }

  public static @NotNull String toKeyNotation(@NotNull List<KeyStroke> keys) {
    if (keys.isEmpty()) {
      return "<Nop>";
    }
    final StringBuilder builder = new StringBuilder();
    for (KeyStroke key : keys) {
      builder.append(toKeyNotation(key));
    }
    return builder.toString();
  }

  public static @NotNull String toKeyNotation(@NotNull KeyStroke key) {
    final char c = key.getKeyChar();
    final int keyCode = key.getKeyCode();
    final int modifiers = key.getModifiers();

    if (c != CHAR_UNDEFINED && !isControlCharacter(c)) {
      return String.valueOf(c);
    }

    String prefix = "";
    if ((modifiers & META_DOWN_MASK) != 0) {
      prefix += "M-";
    }
    if ((modifiers & ALT_DOWN_MASK) != 0) {
      prefix += "A-";
    }
    if ((modifiers & CTRL_DOWN_MASK) != 0) {
      prefix += "C-";
    }
    if ((modifiers & SHIFT_DOWN_MASK) != 0) {
      prefix += "S-";
    }

    String name = getVimKeyValue(keyCode);
    if (name != null) {
      if (containsDisplayUppercaseKeyNames(name)) {
        name = name.toUpperCase();
      }
      else {
        name = StringUtil.capitalize(name);
      }
    }
    if (name == null) {
      final String escape = toEscapeNotation(key);
      if (escape != null) {
        return escape;
      }

      try {
        name = String.valueOf(Character.toChars(keyCode));
      }
      catch (IllegalArgumentException ignored) {
      }
    }

    return name != null ? "<" + prefix + name + ">" : "<<" + key + ">>";
  }

  public static String toPrintableCharacters(@NotNull List<KeyStroke> keys) {
    if (keys.isEmpty()) {
      return "";
    }
    final StringBuilder builder = new StringBuilder();
    for (KeyStroke key : keys) {
      builder.append(toPrintableCharacter(key));
    }
    return builder.toString();
  }

  /**
   * Convert a KeyStroke into the character it represents and return a printable version of the character.
   *
   * See :help 'isprint'
   *
   * @param key The KeyStroke to represent
   * @return A printable String of the character represented by the KeyStroke
   */
  public static String toPrintableCharacter(@NotNull KeyStroke key) {
    // TODO: Look at 'isprint', 'display' and 'encoding' settings
    char c = key.getKeyChar();
    if (c == CHAR_UNDEFINED && key.getModifiers() == 0) {
      c = (char)key.getKeyCode();
    }
    else if (c == CHAR_UNDEFINED && (key.getModifiers() & CTRL_DOWN_MASK) != 0) {
      c = (char)(key.getKeyCode() - 'A' + 1);
    }

    if (c <= 31) {
      return "^" + (char) (c + 'A' - 1);
    } else if (c == 127) {
      return "^" + (char) (c - 'A' + 1);
      // Vim doesn't use these representations unless :set encoding=latin1. Technically, we could use them if the
      // encoding of the buffer for the mark, jump or :ascii char is. But what encoding would we use for registers?
      // Since we support Unicode, just treat everything as Unicode.
//    } else if (c >= 128 && c <= 159) {
//      return "~" + (char) (c - 'A' + 1);
//    } else if (c >= 160 && c <= 254) {
//      return "|" + (char)(c - (('A' - 1) * 2));
//    } else if (c == 255) {
//      return "~" + (char)(c - (('A' - 1) * 3));
    } else if (CharacterHelper.isInvisibleControlCharacter(c) || CharacterHelper.isZeroWidthCharacter(c)) {
      return String.format("<%04x>", (int) c);
    }
    return String.valueOf(c);
  }

  public static boolean containsUpperCase(@NotNull String text) {
    for (int i = 0; i < text.length(); i++) {
      if (Character.isUpperCase(text.charAt(i)) && (i == 0 || text.charAt(i - 1) != '\\')) {
        return true;
      }
    }

    return false;
  }

  public static boolean isCloseKeyStroke(@NotNull KeyStroke key) {
    return key.getKeyCode() == VK_ESCAPE ||
           key.getKeyChar() == VK_ESCAPE ||
           key.getKeyCode() == VK_C && (key.getModifiers() & CTRL_DOWN_MASK) != 0 ||
           key.getKeyCode() == '[' && (key.getModifiers() & CTRL_DOWN_MASK) != 0;
  }

  /**
   * Set the text of an XML element, safely encode it if needed.
   */
  public static @NotNull Element setSafeXmlText(@NotNull Element element, @NotNull String text) {
    element.setAttribute("encoding", "base64");
    final String encoded = new String(Base64.encodeBase64(text.getBytes()));
    element.setText(encoded);
    return element;
  }

  /**
   * Get the (potentially safely encoded) text of an XML element.
   */
  public static @Nullable String getSafeXmlText(@NotNull Element element) {
    final String text = element.getText();
    final String encoding = element.getAttributeValue("encoding");
    if (encoding == null) {
      return text;
    }
    else if (encoding.equals("base64")) {
      return new String(Base64.decodeBase64(text.getBytes()));
    }
    return null;
  }

  private static @Nullable KeyStroke parseSpecialKey(@NotNull String s, int modifiers) {
    final String lower = s.toLowerCase();
    final Integer keyCode = getVimKeyName(lower);
    final Character typedChar = getVimTypedKeyName(lower);
    if (keyCode != null) {
      return getKeyStroke(keyCode, modifiers);
    }
    else if (typedChar != null) {
      return getTypedOrPressedKeyStroke(typedChar, modifiers);
    }
    else if (lower.startsWith(META_PREFIX)) {
      return parseSpecialKey(s.substring(META_PREFIX.length()), modifiers | META_DOWN_MASK);
    }
    else if (lower.startsWith(ALT_PREFIX)) {
      return parseSpecialKey(s.substring(ALT_PREFIX.length()), modifiers | ALT_DOWN_MASK);
    }
    else if (lower.startsWith(CTRL_PREFIX)) {
      return parseSpecialKey(s.substring(CTRL_PREFIX.length()), modifiers | CTRL_DOWN_MASK);
    }
    else if (lower.startsWith(SHIFT_PREFIX)) {
      return parseSpecialKey(s.substring(SHIFT_PREFIX.length()), modifiers | SHIFT_DOWN_MASK);
    }
    else if (s.length() == 1) {
      return getTypedOrPressedKeyStroke(s.charAt(0), modifiers);
    }
    return null;
  }

  private static boolean containsDisplayUppercaseKeyNames(String lower) {
    return "cr".equals(lower) || "bs".equals(lower);
  }

  private static Character getVimTypedKeyName(String lower) {
    switch (lower) {
      case "space":
        return ' ';
      case "bar":
        return '|';
      case "bslash":
        return '\\';
      case "lt":
        return '<';
      default:
        return null;
    }
  }

  private static Integer getVimKeyName(@NonNls String lower) {
    switch (lower) {
      case "cr":
      case "enter":
      case "return":
        return VK_ENTER;
      case "ins":
      case "insert":
        return VK_INSERT;
      case "home":
        return VK_HOME;
      case "end":
        return VK_END;
      case "pageup":
        return VK_PAGE_UP;
      case "pagedown":
        return VK_PAGE_DOWN;
      case "del":
      case "delete":
        return VK_DELETE;
      case "esc":
        return VK_ESCAPE;
      case "bs":
      case "backspace":
        return VK_BACK_SPACE;
      case "tab":
        return VK_TAB;
      case "up":
        return VK_UP;
      case "down":
        return VK_DOWN;
      case "left":
        return VK_LEFT;
      case "right":
        return VK_RIGHT;
      case "f1":
        return VK_F1;
      case "f2":
        return VK_F2;
      case "f3":
        return VK_F3;
      case "f4":
        return VK_F4;
      case "f5":
        return VK_F5;
      case "f6":
        return VK_F6;
      case "f7":
        return VK_F7;
      case "f8":
        return VK_F8;
      case "f9":
        return VK_F9;
      case "f10":
        return VK_F10;
      case "f11":
        return VK_F11;
      case "f12":
        return VK_F12;
      case "plug":
        return VK_PLUG;
      case "action":
        return VK_ACTION;
      default:
        return null;
    }
  }

  private static @NonNls String getVimKeyValue(int c) {
    switch (c) {
      case VK_ENTER:
        return "cr";
      case VK_INSERT:
        return "ins";
      case VK_HOME:
        return "home";
      case VK_END:
        return "end";
      case VK_PAGE_UP:
        return "pageup";
      case VK_PAGE_DOWN:
        return "pagedown";
      case VK_DELETE:
        return "del";
      case VK_ESCAPE:
        return "esc";
      case VK_BACK_SPACE:
        return "bs";
      case VK_TAB:
        return "tab";
      case VK_UP:
        return "up";
      case VK_DOWN:
        return "down";
      case VK_LEFT:
        return "left";
      case VK_RIGHT:
        return "right";
      case VK_F1:
        return "f1";
      case VK_F2:
        return "f2";
      case VK_F3:
        return "f3";
      case VK_F4:
        return "f4";
      case VK_F5:
        return "f5";
      case VK_F6:
        return "f6";
      case VK_F7:
        return "f7";
      case VK_F8:
        return "f8";
      case VK_F9:
        return "f9";
      case VK_F10:
        return "f10";
      case VK_F11:
        return "f11";
      case VK_F12:
        return "f12";
      case VK_PLUG:
        return "plug";
      case VK_ACTION:
        return "action";
      default:
        return null;
    }
  }

  private static @NotNull KeyStroke getTypedOrPressedKeyStroke(char c, int modifiers) {
    if (modifiers == 0) {
      return getKeyStroke(c);
    }
    else if (modifiers == SHIFT_DOWN_MASK && Character.isLetter(c)) {
      return getKeyStroke(Character.toUpperCase(c));
    }
    else {
      return getKeyStroke(Character.toUpperCase(c), modifiers);
    }
  }
}
