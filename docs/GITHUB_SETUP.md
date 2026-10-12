# 공개 GitHub 저장소와 이어하기

인증된 choijunhuk 계정으로 [coms-companion](https://github.com/choijunhuk/coms-companion) Public 저장소와 main 첫 커밋을 게시했다. 다음 세션은 새 저장소를 생성하지 않고 현재 Git/CI와 STATUS.md를 확인해 이어간다. 아래는 첫 게시 절차 기록이다.

## GitHub CLI로 처음 게시하는 경우

Git과 GitHub CLI가 설치된 환경에서 GitHub CLI 로그인을 완료합니다. 로그인 비밀값은 채팅이나 문서에 기록하지 않습니다.

1. 프로젝트 폴더로 이동합니다.
2. gh auth status로 로그인 상태를 확인합니다.
3. gh api user --jq .login으로 게시할 소유자를 확인합니다.
4. git status와 git diff --cached로 공개할 파일을 확인합니다. git 저장소가 없을 때만 git init -b main으로 초기화합니다.
5. 필요한 파일을 선택해 추가한 뒤 첫 커밋을 만듭니다. 예: git commit -m "docs: define Android club operations app and development handoff"
6. 기존 origin이나 같은 이름의 저장소가 없는 신규 상황에서만 아래 명령을 실행합니다.

    gh repo create coms-companion --public --source=. --remote=origin --push

이미 origin이 있으면 새 저장소를 생성하지 말고, 올바른 소유자와 저장소인지 확인한 뒤 해당 원격을 사용합니다. 같은 이름의 다른 프로젝트가 있으면 덮어쓰지 않고 구분되는 이름을 정합니다.

## 웹에서 저장소를 생성하는 경우

GitHub의 새 저장소 화면에서 이름을 coms-companion, 공개 범위를 Public으로 지정합니다. 기존 로컬 이력을 푸시할 계획이면 README나 라이선스를 별도로 자동 생성하지 않고 빈 저장소로 시작하면 이력 충돌을 줄일 수 있습니다.

생성이 끝난 뒤 GitHub가 안내한 정확한 원격 주소로 로컬 origin을 연결하고 main 브랜치를 푸시합니다. 존재하지 않는 주소를 생성 완료 링크로 기록하지 않습니다.

## 게시 후 기록

- 원격 URL, 기본 브랜치, 게시된 커밋과 현재 구현 상태를 STATUS.md에 기록합니다.
- 다음 세션은 같은 저장소를 열고 AGENTS.md, META_PROMPT.md, STATUS.md부터 읽습니다.
- 공개 소스 저장소와 회원 데이터 저장 공간을 구분합니다.
- 앱 서명키는 별도 안전한 장소에 보관하고, 검증된 APK는 GitHub Releases 등 선택한 배포 경로에서 제공할 수 있습니다. 첫 디버그 APK는 CI 아티팩트로 확인할 수 있습니다. 실기 승인·안정된 릴리스 서명은 별개이며 현재 STATUS.md를 확인합니다.

공식 명령 참고: [GitHub CLI — gh repo create](https://cli.github.com/manual/gh_repo_create)
