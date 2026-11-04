# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

> 1~4장 출처: [andrej-karpathy-skills](https://github.com/multica-ai/andrej-karpathy-skills)의 `CLAUDE.md`를 그대로 옮겼다.

---

## 5. 와이퍼넷 프로젝트 규칙

### 작업 순서
구현 단위마다 아래 순서를 지키고, 확인 단계에서는 사용자 답을 받을 때까지 멈춘다.
1. 현재 브랜치와 기능지도를 확인한다.
2. 이번에 구현할 범위를 사용자에게 알리고 **확인**을 받는다.
3. 구현하고 테스트한다. 기능지도에 반영할 내용이 있으면 함께 고친다.
4. 결과를 보고하고 **확인**을 받은 뒤 커밋하고 PR을 연다.

### 기능지도
- **구현 전에** [`docs/features/README.md`](docs/features/README.md)를 읽고, 바꿀 기능이 어디에 있고 무엇과 연결되는지(의존 관계 절) 확인한다.
- **구현 후에** 같은 PR 안에서 기능지도를 고친다. 상태 표기, 코드 위치, 테스트, 에뮬레이터 확인 결과, 의존 관계를 현재 상태로 맞춘다. 변경 이력은 쓰지 않는다(git과 PR이 맡는다).

### 브랜치와 PR
- `main`은 항상 제출 가능한 상태다. main에 직접 커밋하지 않는다.
- 계획의 Task 하나당 브랜치 하나: `{type}/task-{NN}-{요약}` (예: `feat/task-02-grid-rain-map`).
- 합칠 때는 GitHub PR을 연다. PR 본문에 한 일, 테스트 결과, 기능지도에서 바뀐 줄을 적는다.
- 합치기 전 확인: 테스트 통과, 기능지도 갱신, 앱 Task라면 에뮬레이터 확인 결과 기록.
- 제출 시점 태그: `round1-submit`(11/15), `round2-pt`(12/3).
- 커밋 메시지는 `feat(server): …`, `fix(app): …` 형식. 커밋 작성자는 `Tae-Geon-Kim`, 이메일은 GitHub noreply 주소(`207544432+Tae-Geon-Kim@users.noreply.github.com`).

### 지켜야 할 것
- 설계: [`docs/2026-10-30-wipernet-design.md`](docs/2026-10-30-wipernet-design.md), 계획: [`docs/superpowers/plans/2026-10-30-wipernet.md`](docs/superpowers/plans/2026-10-30-wipernet.md).
- 공개물(README, 커밋, 코드, 문서)에 사용자 실명·연락처를 넣지 않는다.
- 서버 저장소는 메모리다. DB·Alembic은 공모전 범위 밖이며, 저장은 `store.py` 경계 뒤에 두어 나중에 바꿀 수 있게 한다.
- Pleos SDK 동작은 추측하지 않는다. 공식 문서(https://document.pleos.ai)와 에뮬레이터에서 확인한 것만 근거로 쓰고, 확인 결과는 기능지도에 남긴다.
- 사용자에게 보이는 문구는 한국어.
