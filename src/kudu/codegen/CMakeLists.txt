# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

################################################################################
# This target relies on special LLVM-configured cmake functions
# see http://llvm.org/docs/CMake.html#embedding-llvm-in-your-project
################################################################################

#######################################
# Configure LLVM-specific dependencies
#######################################

set(LLVM_REQ_COMPONENTS
  analysis
  irreader
  instrumentation
  ipo
  mcdisassembler
  mcjit
  native
)

## Add preprocessor defs and include directories
include_directories(SYSTEM ${LLVM_INCLUDE_DIRS})
add_definitions(${LLVM_DEFINITIONS})

# Workaround for a conflict between LLVM's Support/Valgrind.h file
# and our dynamic_annotations.h. Defining this prevents the LLVM
# header from getting included.
add_definitions(-DLLVM_SUPPORT_VALGRIND_H)

## Get the required libraries to link to in llvm
llvm_map_components_to_libnames(llvm_LIBRARIES "${LLVM_REQ_COMPONENTS}")

#######################################
# Precompiling to LLVM bytecode
#######################################

## Create .ll file for precompiled functions (and their dependencies).
set(CLANG_EXEC ${THIRDPARTY_DIR}/clang-toolchain/bin/clang++)
set(IR_SOURCE ${CMAKE_CURRENT_SOURCE_DIR}/precompiled.cc)
set(IR_OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/precompiled.ll)
set(IR_OUTPUT_CC ${IR_OUTPUT}.cc)

# Retrieve all includes directories needed for precompilation.
get_directory_property(IR_INCLUDES
  DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
  INCLUDE_DIRECTORIES)
foreach(noprefix ${IR_INCLUDES})
  set(PREFIXED_IR_INCLUDES ${PREFIXED_IR_INCLUDES} -I${noprefix})
endforeach()

if (APPLE)
  # The macOS keeps the libc++ headers in a non-standard location so
  # that the thirdparty CLANG does not know about by default.
  #
  # Xcode starting with version 12.5 has the libc++ headers under
  # Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk,
  # which corresponds to CMAKE_OSX_SYSROOT, so it's enough to add -isysroot
  # pointing to CMAKE_OSX_SYSROOT.
  #
  # Xcode prior to version 12.5 (12.4 and earlier, inclusive) doesn't have the
  # libc++ headers under CMAKE_OSX_SYSROOT, but instead keeps those under
  # Contents/Developer/Toolchains/XcodeDefault.xctoolchain: with that,
  # it's easy to deduce the path to the libc++ headers from the output
  # produced by `clang++ --version`.
  #
  # For non-clang compilers, assume the libc++ include directory provided
  # with the Xcode command line tools.
  if (NOT "${COMPILER_FAMILY}" STREQUAL "clang")
    set(PREFIXED_IR_INCLUDES
      ${PREFIXED_IR_INCLUDES}
      -cxx-isystem "/Library/Developer/CommandLineTools/usr/include/c++/v1")
  elseif (${CMAKE_CXX_COMPILER_VERSION} VERSION_LESS 12.0.5)
    execute_process(
      COMMAND ${CMAKE_CXX_COMPILER} --version
      COMMAND grep -E "^InstalledDir: "
      COMMAND sed "s/^InstalledDir: \\(.*\\)$/\\1/"
      RESULT_VARIABLE CXX_INSTALLED_DIR_CMD_EXIT_CODE
      OUTPUT_VARIABLE CXX_INSTALLED_DIR_CMD_OUT
      OUTPUT_STRIP_TRAILING_WHITESPACE)
    if (${CXX_INSTALLED_DIR_CMD_EXIT_CODE} EQUAL 0 AND
        NOT ${CXX_INSTALLED_DIR_CMD_OUT} STREQUAL "")
      set(PREFIXED_IR_INCLUDES
        ${PREFIXED_IR_INCLUDES}
        -cxx-isystem "${CXX_INSTALLED_DIR_CMD_OUT}/../include/c++/v1")
    else()
      message(FATAL_ERROR "failed to deduce path to libc++ headers")
    endif()
  else()
    set(PREFIXED_IR_INCLUDES
      ${PREFIXED_IR_INCLUDES}
      -isysroot "${CMAKE_OSX_SYSROOT}")
  endif()
endif()

# Get preprocessing definitions, which enable directives for glog and gtest.
get_directory_property(IR_PP_DEFINITIONS
  DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}
  COMPILE_DEFINITIONS)
foreach(noprefix ${IR_PP_DEFINITIONS})
    set(PREFIXED_IR_PP_DEFS ${PREFIXED_IR_PP_DEFS} -D${noprefix})
endforeach()

# Get flags related to actually compiling the source.
set(IR_FLAGS
  -S
  -emit-llvm
  -DIR_BUILD
  ${CMAKE_CXX_FLAGS}
  ${PREFIXED_IR_PP_DEFS}
  ${PREFIXED_IR_INCLUDES})

# /usr/include doesn't have the necessary headers on macOS 10.14 (Mojave) with
# Xcode 10[1]+. In this case the --sysroot has to be set to the output of "xcrun
# --show-sdk-path" for the thirdparty clang-6.0 to work correctly.
#
# [1] https://developer.apple.com/documentation/xcode_release_notes/xcode_10_release_notes
if (APPLE AND NOT EXISTS /usr/include/libc.h)
  set(IR_FLAGS
    ${IR_FLAGS}
    --sysroot="${CMAKE_OSX_SYSROOT}")
endif()

# Provide clang with any explicitly defined system prefix path. If there isn't
# one, clang will search for it on its own.
if(COMPILER_SYSTEM_PREFIX_PATH)
  set(IR_FLAGS ${IR_FLAGS} --gcc-toolchain=${COMPILER_SYSTEM_PREFIX_PATH})
endif()

separate_arguments(IR_FLAGS)

# Avoid enabling ASAN in the precompiled IR.
#
# This avoids an issue when the main code is compiled with a different version
# of LLVM than we are using for JIT, and ASAN is enabled. In that case,
# the IR code will try to call __asan_init_v<X> while our runtime code will
# only have defined __asan_init_v<Y> (where X != Y).
#
# Disabling -fsanitize-address will prevent the instrumentation, so it doesn't
# try to link against these symbols.
#
# NOTE: we leave "-DADDRESS_SANITIZER" because this enables ASAN annotations
# from dynamic_annotations.h. These annotations are just extern function
# declarations which will link fine against the ASAN in our executable, even
# if the JIT code is not instrumented.
list(REMOVE_ITEM IR_FLAGS "-fsanitize=address")

# Disable TSAN in precompiled IR.
#
# Protobuf 2.6.1's atomicops-internals-tsan.h relies on
# <sanitizer/tsan_interface_atomic.h>, which is not provided by the LLVM version
# we use for codegen.
list(REMOVE_ITEM IR_FLAGS "-fsanitize=thread" "-DTHREAD_SANITIZER")

# Remove any optimization flags from the generated IR.
# Optimizing during the precompilation limits the ability to optimize
# again at runtime.
list(REMOVE_ITEM IR_FLAGS "-O3" "-O2" "-O1" "-Os")

# If present (see the top-level CMakeLists.txt), this has no effect and just generates
# an unused argument warning.
list(REMOVE_ITEM IR_FLAGS "-fuse-ld=gold")

# Disable built-in LLVM passes which would add 'noinline' attributes to all
# standalone functions.
#
# This is per the advice in https://reviews.llvm.org/D28053#629914 which says:
#
#   "This will generate a frontend-optimized but backend pristine bitcode file that
#    can be processed more or less depending on the desire of the user..."
list(APPEND IR_FLAGS "-O" "-mllvm" "-disable-llvm-optzns")

# We need a library which depends on the IR source, because CMake+Ninja
# doesn't support IMPLICIT_DEPENDS in ADD_CUSTOM_COMMAND.
#
# Using a fake target like this allows us to pick up the dependencies
# of precompiled.ll, and then we make the IR generation depend on the fake
# target. We end up doing one extra compilation, but that's better than
# having stale IR.
#
# See: http://www.cmake.org/Bug/bug_relationship_graph.php?bug_id=13234
add_library(ir_fake_target ${IR_SOURCE})
# The IR uses protobufs from kudu_common, so we have to generate that code first.
target_link_libraries(ir_fake_target kudu_common_proto kudu_util ${KUDU_BASE_LIBS})

add_custom_command(
  OUTPUT ${IR_OUTPUT}
  COMMAND ${CLANG_EXEC}
    ${IR_FLAGS}
    ${IR_SOURCE}
    -o ${IR_OUTPUT}
  DEPENDS ir_fake_target)

# Use 'xxd' to create a cc file containing the precompiled bitcode as a literal array.
# See http://stackoverflow.com/questions/4158900/embedding-resources-in-executable-using-gcc
add_custom_command(
  OUTPUT ${IR_OUTPUT_CC}
  COMMAND ${CMAKE_SOURCE_DIR}/build-support/generate_precompiled_xxd.sh ${IR_OUTPUT} ${IR_OUTPUT_CC}
  DEPENDS ${IR_OUTPUT})

#######################################
# codegen
#######################################

add_library(codegen
  code_cache.cc
  code_generator.cc
  compilation_manager.cc
  jit_wrapper.cc
  module_builder.cc
  row_projector.cc
  ${IR_OUTPUT_CC})

target_link_libraries(codegen
  ${llvm_LIBRARIES}
  kudu_common
  kudu_util
  gutil
  kudu_common_proto)

#######################################
# Unit tests
#######################################

SET_KUDU_TEST_LINK_LIBS(codegen)

ADD_KUDU_TEST(codegen-test)
