cmake_minimum_required(VERSION 3.13)
project(komorebi-native)

set(CMAKE_CXX_STANDARD 17)

set(ARIBCC_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ARIBCC_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(ARIBCC_NO_RENDERER OFF CACHE BOOL "" FORCE)
set(ARIBCC_USE_EMBEDDED_FREETYPE ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5 CACHE STRING "" FORCE)

# 非公式パッチ: 字幕フォントをアプリ同梱フォントのファイルパスで指定できるようにする。
# libaribcaption の FontProviderAndroid はフォント名を /system/etc/fonts.xml のファミリ名としてしか
# 解決しないため、「先頭が '/' ならファイルパスとしてそのまま返す」分岐を configure 時にソースへ挿入する
# (submodule 側は改変せずに済ませたいので、冪等なテキスト置換で当てる)。
set(_ARIBCC_FONT_PROVIDER_ANDROID "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libaribcaption/src/renderer/font_provider_android.cpp")
if(EXISTS "${_ARIBCC_FONT_PROVIDER_ANDROID}")
    file(READ "${_ARIBCC_FONT_PROVIDER_ANDROID}" _ARIBCC_FP_SRC)
    string(FIND "${_ARIBCC_FP_SRC}" "KOMOREBI_FONT_PATH_PATCH" _ARIBCC_FP_PATCHED)
    if(_ARIBCC_FP_PATCHED EQUAL -1)
        set(_ARIBCC_FP_ANCHOR "    FontFile* font_file = nullptr;\n")
        set(_ARIBCC_FP_INSERT "    // KOMOREBI_FONT_PATH_PATCH: フォント名が絶対パスならそのファイルをそのまま使う\n    if (!font_name.empty() && font_name[0] == '/') {\n        FontfaceInfo path_info;\n        path_info.family_name = font_name;\n        path_info.filename = font_name;\n        path_info.face_index = 0;\n        path_info.provider_type = FontProviderType::kAndroid;\n        return Ok(std::move(path_info));\n    }\n")
        string(REPLACE "${_ARIBCC_FP_ANCHOR}" "${_ARIBCC_FP_ANCHOR}${_ARIBCC_FP_INSERT}" _ARIBCC_FP_SRC "${_ARIBCC_FP_SRC}")
        file(WRITE "${_ARIBCC_FONT_PROVIDER_ANDROID}" "${_ARIBCC_FP_SRC}")
        message(STATUS "libaribcaption: FontProviderAndroid にフォントパス指定パッチを適用しました")
    endif()
endif()

add_subdirectory(third_party/libaribcaption EXCLUDE_FROM_ALL)

# 1. tsreadex のコアロジック（ソースファイル群）を定義
set(TSREADEX_CORE_SRC
    util.cpp
    id3conv.cpp
    servicefilter.cpp
    aac.cpp
    huffman.cpp
    traceb24.cpp
)

# 2. JNI の橋渡し役となる native-lib.cpp とコアロジックをまとめてライブラリにする
add_library(
    komorebi-native
    SHARED
    native-lib.cpp
    ${TSREADEX_CORE_SRC}
)

# 3. ログ出力用のライブラリをリンク（Android の Logcat 用）
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    komorebi-native
    ${log-lib}
    ${jnigraphics-lib}
    aribcaption::aribcaption
)

# インクルードディレクトリの設定（カレントディレクトリを参照）
target_include_directories(komorebi-native PRIVATE .)
