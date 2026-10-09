@echo off
rem Builds build\native\duelcraft_native.dll with MSVC:
rem   third_party\ocgcore (EDOPro's duel engine, AGPL-3.0) + native\dc_texture.cpp (BC7 via bcdec, MIT).
rem Lua is compiled as C++ with the engine's luaconf-customize.h prepended, as its meson.build does.
setlocal
set ROOT=%~dp0..
set SRC=%ROOT%\third_party\ocgcore
set OUT=%ROOT%\build\native
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
if not exist "%OUT%\luagen" mkdir "%OUT%\luagen"
for %%f in (lapi lauxlib lbaselib lcode lctype ldebug ldo ldump lfunc lgc liolib llex lmathlib lmem lobject lopcodes lparser lstate lstring lstrlib ltable ltablib ltm lundump lvm lzio) do (
  copy /b "%SRC%\lua\luaconf-customize.h" + "%SRC%\lua\src\%%f.c" "%OUT%\luagen\%%f.cpp" >nul
)
pushd "%OUT%"
cl /nologo /O2 /MT /std:c++17 /EHsc /GR- /W0 /DOCGCORE_EXPORT_FUNCTIONS /I"%SRC%\lua\src" /I"%SRC%\lua" /I"%SRC%" /I"%ROOT%\third_party\bcdec" ^
  luagen\*.cpp "%SRC%\card.cpp" "%SRC%\duel.cpp" "%SRC%\effect.cpp" "%SRC%\field.cpp" "%SRC%\interpreter.cpp" ^
  "%SRC%\libcard.cpp" "%SRC%\libdebug.cpp" "%SRC%\libduel.cpp" "%SRC%\libeffect.cpp" "%SRC%\libgroup.cpp" ^
  "%SRC%\ocgapi.cpp" "%SRC%\operations.cpp" "%SRC%\playerop.cpp" "%SRC%\processor.cpp" "%SRC%\processor_visit.cpp" ^
  "%SRC%\scriptlib.cpp" "%ROOT%\native\dc_texture.cpp" /LD /Fe:duelcraft_native.dll /link /NOLOGO
set ERR=%ERRORLEVEL%
popd
if %ERR% neq 0 exit /b %ERR%
if not exist "%ROOT%\fabric\src\main\resources\natives" mkdir "%ROOT%\fabric\src\main\resources\natives"
copy /y "%OUT%\duelcraft_native.dll" "%ROOT%\fabric\src\main\resources\natives\duelcraft_native.dll" >nul
exit /b 0
