// Copyright 2025 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package net.starlark.java.eval;

import java.util.Objects;
import net.starlark.java.types.StarlarkType;
import net.starlark.java.types.Types;

/** Type checker for Starlark types. */
public final class TypeChecker {

  public static boolean isSubtypeOf(StarlarkType type1, StarlarkType type2) {
    // Primitive unification, this way the lattice doesn't collapse
    if (Objects.equals(type1, Types.ANY)) {
      type1 = type2;
    } else if (Objects.equals(type2, Types.ANY)) {
      type2 = type1;
    }
    // TODO(ilist@): this just works for primitive types
    return Objects.equals(type1, type2);
  }

  static boolean isValueSubtypeOf(Object value, StarlarkType type2) {
    return isSubtypeOf(type(value), type2);
  }

  static StarlarkType type(Object value) {
    if (value instanceof StarlarkValue val) {
      StarlarkType type = val.getStarlarkType();
      // Workaround for test mocks that generate getStarlarkType returning null
      return Objects.requireNonNullElse(type, Types.ANY);
    }
    if (value instanceof Boolean) {
      return Types.BOOL;
    }
    if (value instanceof String) {
      return Types.STR;
    }
    throw new IllegalArgumentException("Expected a valid Starlark value.");
  }

  private TypeChecker() {}
}
