# GUI 작업 도구와 훅 호환

기준일: 2026-10-13. 앱 구현/검증은 STATUS.md, 화면 기준은 UI_UX.md를 따른다. 디자인 검색 결과가 기존 화면 명세나 개인정보 경계를 덮어쓰지 않는다.

## 사용할 수 있는 도구

- UI UX Pro Max를 Codex 표준 사용자 스킬 경로 `~/.codex/skills/ui-ux-pro-max`에 설치했다. [공식 upstream](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) 커밋 `7246805992a42b7bba37d4cac83b01416f0c6c44`을 고정해 받았다. Python 표준 라이브러리로 스타일·타이포그래피·접근성·스택 지침을 오프라인 검색한다. React/Jetpack Compose 검색 각각 3건 반환을 확인했다. 새 세션/다음 턴에서 스킬을 사용할 수 있다.
- frontend-design와 Figma 플러그인은 기존에 활성화되어 있다. Figma 실제 계정 연결과 파일 작성은 별도 범위다.
- [Microsoft Playwright MCP](https://github.com/microsoft/playwright-mcp)는 이미 설치되어 있어 재설치하지 않았다. 실제 로컬 서버의 health/회원 응답 웹 접속을 확인했다. 모바일 창 크기·접근성 스냅샷·화면 캡처에 사용한다. `.playwright-mcp/` 산출물은 공개 Git에서 제외한다.
- Android는 설치된 SDK의 ADB와 UIAutomator/스크린샷으로 검증한다. `adb -s emulator-5580`처럼 대상을 고정한다. 중복된 제3자 Android MCP를 추가해 다른 연결 기기를 자동 선택하지 않는다.

```sh
python3 ~/.codex/skills/ui-ux-pro-max/scripts/search.py 'state rememberSaveable' --stack jetpack-compose --json
python3 ~/.codex/skills/ui-ux-pro-max/scripts/search.py 'form accessibility' --stack react --json
```

검색 결과에는 적용 버전/참고 시점이 있으므로 실제 API 변경은 해당 스택의 공식 문서를 확인한다. 앱 패키지 의존성이나 유료 API를 추가하지 않았다.

## 반복 PreToolUse 실패 복구

활성 OMC 5.7.0의 `scripts/pre-tool-enforcer.mjs`가 Claude용 `continue`/`suppressOutput`을 Codex PreToolUse JSON에 출력했다. 지원하지 않는 최상위 필드 두 개를 모든 경로에서 제거했다. `hookSpecificOutput`의 안내·입력 갱신·`permissionDecision: deny`는 보존한다. 훅을 통째로 끄거나 승인/차단 정책을 우회하지 않았다.

원본은 사용자 Codex 백업 폴더에 보관했다. 후보와 활성 스크립트 모두에서 일반 도구, Bash 안내/반복 호출, 작업 갱신, Agent 차단을 격리해 검사했다. 유효 JSON과 deny 보존이 통과했다. 후속 실제 MCP/도구 호출도 정상 실행했다. `omx doctor`: 21 pass, 0 failed, 1 warning. 경고는 기존 multi-agent 설정 소유권에 관한 것이므로 현재 동작하는 설정을 삭제하지 않았다.

이 변경은 이 저장소의 앱 코드가 아니라 로컬 Codex 플러그인 캐시 호환 수정이다. 플러그인 업데이트가 캐시를 교체하면 새 버전 출력과 차단 테스트를 다시 확인한다. 별도의 MCP OAuth 미연결 경고를 같은 훅 오류로 취급하지 않는다. 실제 자격 증명과 로컬 설정 백업은 공개 저장소에 넣지 않는다.
