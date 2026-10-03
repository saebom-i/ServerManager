on run argv
 set defaultPath to item 1 of argv
 set answer to button returned of (display dialog "서버 프로필·월드·모드·계정을 저장할 폴더를 선택하세요. 기존 Server Manager 데이터 폴더도 사용할 수 있습니다.\n\n기본 위치: " & defaultPath buttons {"취소", "기본 위치", "폴더 선택"} default button "폴더 선택" cancel button "취소" with title "Server Manager 프로필 위치")
 if answer is "기본 위치" then return defaultPath
 set selectedFolder to choose folder with prompt "빈 폴더 또는 기존 Server Manager 프로필 폴더 선택"
 return POSIX path of selectedFolder
end run
