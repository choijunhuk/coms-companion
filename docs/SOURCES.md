# 공식 출처와 설계 근거

기본 확인일: **2026-10-12**. 카카오 Android 설정·공유 문서는 **2026-10-14** 재확인했다.

이 문서는 공식 출처 기록이다. 첫 구현/검증은 STATUS.md에 있으며 실기 문자 발송은 미검증이다. 공식 사실·설계 결정·미확인 사항을 구분한다.

## 1. SKT 기본 제공 문자와 자동발송

- [SKT 서비스 이용안내](https://m.tworld.co.kr/customer/svc-info/service/detail?code=C00015): 문자 무제한 요금제에서 물리적 장치 또는 자동발송 프로그램을 통한 메시지 발송을 제한한다. 별도의 일일 발송량 제한과 수신처 수에 따른 판단 기준도 안내한다. **일 500건 등의 수치는 자동발송 허용량이 아니며, 그 미만이면 프로그램 발송이 허용된다고 해석할 수 없다.**
- [SKT 다이렉트5G 55 유의사항](https://m.tworld.co.kr/product/callplan?prod_id=NA00008099): 문자 기본 제공 제한 사유에 특정 장치·자동발송 프로그램 이용이 별도로 포함된다. 혜택 제한 후에는 실제 발송 건수에 따라 과금될 수 있다. 특정 직업군의 예외 신청 안내가 있으나, 대학 동아리에 자동 적용된다는 근거는 없다. 이 페이지는 실제 요금제의 사례이며 사용자가 가입한 상품으로 확인된 것은 아니다.

**설계 권장:** 본인 SIM으로 발송한다는 기술적 사실과 기본 제공 혜택이 적용된다는 계약상 판단을 분리한다. 사용자 확인이 있는 기본 문자 앱 전달 방식으로 먼저 완성하고, 일괄 자동발송은 정확한 요금제와 사용 방식의 허용 여부를 확인한 뒤 활성화한다. 발송 간격 조절이나 소량 제한을 통신사 정책 우회 또는 허용 근거로 삼지 않는다.

## 2. Android 직접 발송·권한·상태

- [SmsManager API](https://developer.android.com/reference/android/telephony/SmsManager): `sendTextMessage()`와 `sendMultipartTextMessage()`에는 `SEND_SMS`가 필요하다. 기본 SMS 앱이 아닌 앱의 발송 내역을 시스템이 SMS Provider에 기록하는 동작도 명시되어 있어, **API 자체가 기본 SMS 앱 지정을 항상 요구하는 것은 아니다.** 긴 본문은 `divideMessage()`로 나누며, 다중 SIM에서 기본 회선이 없으면 실패할 수 있다. 전송·전달 결과용 `PendingIntent`를 구분해서 처리한다.
- [SEND_SMS 권한 정의](https://developer.android.com/reference/android/Manifest.permission#SEND_SMS): `dangerous`이면서 `hard restricted` 권한이다. 설치 관리자의 허용 목록 조건이 있으므로 APK 직접 설치만으로 사용 가능성이 보장되지 않는다.
- [Android 권한 개요](https://developer.android.com/guide/topics/permissions/overview): 런타임 권한은 확인·요청이 필요하며, 이미 허용되었을 것이라고 가정해서는 안 된다.
- [Common intents: 문자 작성](https://developer.android.com/guide/components/intents-common#Messaging): `ACTION_SENDTO`, `smsto:` URI, `sms_body`로 기본 문자 앱의 작성 화면에 번호와 본문을 전달할 수 있다.

**설계 권장:** SIM 선택과 권한 거절·철회를 처리한다. 기본 문자 앱을 열었다는 사실을 발송 성공으로 표시하지 않는다. 직접 발송에서도 전송 결과, 전달 보고, 사람이 실제로 읽거나 응답한 상태는 별도로 관리한다. 긴 문자의 분할·표시·과금은 실제 단말과 회선에서 검증한다.

### 과거 SOLAPI 자료의 적용 범위

[SOLAPI 가격 정책](https://solapi.com/pricing), [문자 QnA](https://solapi.com/guides/sms-faq), [공식 발송 예제](https://solapi.com/developers/sdk/nodejs-sendingexample)는 인터넷 문자 서비스의 EUC-KR 바이트 제한, 이모지 제약, 웹발신 표시와 제목 처리 방식을 설명한다. **이전 설계의 SMS 90바이트/LMS 2,000바이트, 이모지 전면 금지, 제목에 따른 LMS 전환, `[Web 발신]` 표시를 Android SIM 발송의 공통 규칙으로 가져오지 않는다.** SIM 전송은 Android API와 실제 통신사·단말 동작을 기준으로 판단한다.

## 3. Google Play와 APK 배포

- [Google Play SMS·통화기록 권한 정책](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en): SMS 권한은 기본 SMS·전화·Assistant 처리 앱 또는 허용된 예외와 심사 요건을 따라야 한다. `Device automation` 예외는 사용자 조건에 따라 OS 여러 영역의 반복 동작을 자동화하는 용도로 설명되어 있으며, 단체문자 도우미가 자동으로 해당하는 것은 아니다. SMS Intent가 대안으로 안내된다.
- [Android 대체 배포 방법](https://developer.android.com/distribute/marketing-tools/alternative-distribution): 웹사이트·서버에서 APK를 배포할 수 있다. Android 8.0 이상에서는 사용자가 설치 출처별로 알 수 없는 앱 설치를 허용해야 한다.

**설계 권장:** 공개 GitHub 소스·서명된 APK 배포와 Play 등록 승인을 별개로 취급한다. APK 배포는 Android 권한이나 SKT 약관의 면제가 아니다. 실제 연락처·문자·토큰·릴리스 서명키는 공개 저장소에 포함하지 않는다.

## 4. 카카오톡 공유·메시지·결과 확인

- [Kakao SDK for Android 시작하기](https://developers.kakao.com/docs/ko/android/getting-started): 네이티브 앱 키와 실제 APK 인증서의 키 해시, Android 패키지를 등록한다. 디버그·릴리스·Play 서명이 다르면 각각의 해시가 필요하다.
- [카카오톡 공유 Android](https://developers.kakao.com/docs/ko/kakaotalk-share/android-link): `shareDefault()`의 성공 결과로 공유 Intent를 열고 사용자가 대상을 선택한다. 링크의 웹 도메인은 앱의 제품 링크 관리에 등록한다. 현재 구현에는 카카오 로그인/OAuth·REST API·어드민 키를 사용하지 않는다.
- [카카오톡 공유 이해하기](https://developers.kakao.com/docs/ko/kakaotalk-share/common): 사용자가 카카오톡에서 친구 또는 채팅방을 선택해 공유한다. Kakao SDK 방식이며 REST API는 지원하지 않는다. 단순 데이터는 OS 공유 기능으로 전달하는 방법도 안내한다.
- [카카오톡 메시지 이해하기](https://developers.kakao.com/docs/ko/kakaotalk-message/common): 같은 서비스의 사용자 사이에 메시지를 보내는 기능이다. 친구 발송에는 동의항목·사용 권한 등 조건이 있고, 임의 전화번호나 모든 기존 단톡방으로 서버가 자유롭게 발송하는 API가 아니다.
- [카카오 쿼터](https://developers.kakao.com/docs/ko/getting-started/quota): 확인 당시 공유의 무료 일간 쿼터는 30,000건이다. 메시지 API에는 앱 쿼터 외에 발신자·수신자·쌍별 제한이 있으며, 사용 권한과 테스트 단계에 따른 조건도 함께 확인해야 한다. 공유의 추가 쿼터에는 유료 사용이 존재한다.
- [카카오톡 공유 웹훅](https://developers.kakao.com/docs/ko/kakaotalk-share/callback): 공유 메시지 전달 성공을 등록된 서버로 알린다. `serverCallbackArgs` 설정이 필요하며, 채팅방의 참고용 해시 ID와 유형 등을 제공한다. 서버는 인증을 확인하고 3초 내 `2XX`로 응답해야 한다. 이 해시 ID를 임의 재발송 권한이나 수신자 실명으로 취급하지 않는다.

**설계 권장:** 기본은 사용자 선택에 따른 공유로 구성한다. 서버 없는 버전에서는 카카오톡을 열고 돌아온 결과만으로 전송 성공을 확정하지 않는다. 웹훅을 추가해도 읽음·답장·참석 확인으로 확대 해석하지 않는다. 비용 없는 사용이 목표이므로 유료 쿼터를 자동 활성화하지 않는다.

## 5. 선택적 로컬 AI

- [Ollama FAQ](https://docs.ollama.com/faq): 로컬 모델 실행 시 프롬프트·데이터를 Ollama가 보지 않는다고 안내한다. `OLLAMA_NO_CLOUD=1` 또는 `disable_ollama_cloud` 설정으로 클라우드 모델과 웹 검색을 끌 수 있다. 기본 서버 주소는 `127.0.0.1:11434`이며 모델 다운로드에는 네트워크가 사용된다.

**설계 권장:** AI 없이 템플릿·변수 치환으로 핵심 기능을 완성한다. 로컬 AI는 선택 기능으로 두고 외부 유료 모델로 자동 전환하지 않는다. PC의 로컬 모델을 휴대전화에서 이용하려면 별도 연결 구성이 필요하며, 추론 API 요금이 없다는 것과 하드웨어·전력 비용이 없다는 것은 구분한다.

## 6. 아직 확인하지 않은 항목

- 사용자의 정확한 SKT 요금제와 문자 기본 제공 범위, 해당 발송 방식에 대한 통신사 확인 결과.
- 실기에서 확인한 SM-S928N/Android16/삼성 문자 앱을 제외한 기기·문자 앱, SIM 구성·설치 경로별 권한 허용 여부.
- 실제 전송·긴 문자 분할·전달 보고·과금 결과. 가상 번호·한글 작성 화면과 앱 복귀는 STATUS.md의 2026-10-14 결과를 따른다.
- 실기에서 확인한 카카오 앱 등록·공식 공유 선택 화면 이후의 실제 전송·수신, 선택적 Ollama 실행·연결 환경.

이 항목들을 확인하기 전에는 무료 자동발송, Play 승인, 전달·읽음 추적, 앱 구현 완료를 확정하지 않는다.

## 7. 첫 구현 버전 선택 (2026-10-12 재확인)

- AGP9.2.1 / Gradle9.4.1 / JDK17 / SDK36. [AGP](https://developer.android.com/build/releases/agp-9-2-0-release-notes), [내장 Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin). Compose compiler2.2.10, KSP2.3.10. 전체 의존성 호환성은 CI 빌드로 최종 확인한다.
- [Compose 설정](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)에 따라 API36용 BOM2026.06.00 선택. 최신 Compose의 API37 요구와 설치 SDK를 구별한다.
- [Room](https://developer.android.com/jetpack/androidx/releases/room)2.8.5, [DataStore](https://developer.android.com/jetpack/androidx/releases/datastore)1.2.1, [WorkManager](https://developer.android.com/jetpack/androidx/releases/work)2.11.2, [Kakao](https://developers.kakao.com/docs/en/android/download)2.25.0. [shareDefault 결과](https://developers.kakao.com/docs/en/kakaotalk-share/android-link)는 공유 Intent 준비이며 실제 전송 확인이 아니다.
- [Node 릴리스](https://nodejs.org/dist/index.json)24.21.0 LTS. 기존 Node25 대신 검증에 사용한다. [내장 SQLite](https://nodejs.org/download/release/latest-v24.x/docs/api/sqlite.html)는 release candidate이며 작은 단일 프로세스에서 사용한다. 동기식 처리량과 업그레이드 호환성을 배포 전 확인한다.
- [Fastify](https://fastify.dev/docs/latest/)5.12.5, React19.3.0, [Vite8](https://vite.dev/blog/announcing-vite8.html)8.3.4, TypeScript7.0.2. 공식 문서와 npm 원본 레지스트리 확인. package.json과 package-lock.json으로 버전/해시 고정.
