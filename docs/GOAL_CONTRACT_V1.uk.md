# Goal Contract v1 — типізовані критерії завершення

## Мета

Goal Contract відокремлює:

```text
TOOL_RESULT succeeded
```

від:

```text
mandatory typed acceptance criteria are verified
```

Це не універсальний довідник істинності довільної природномовної мети. v1 має
`coverage=TYPED_OPERATIONAL_V1` і прямо повідомляє, що passing усіх критеріїв
не є повним semantic/business proof.

## Типи

- `GoalContract`
- `AcceptanceCriterion`
- `CriterionEvidence`
- `CriterionStatus = PENDING | PASSED | FAILED | UNKNOWN`
- `VerificationStrength = TOOL_RESULT | INDEPENDENT_TOOL_RESULT`

Початкові criterion kinds:

- `OPERATIONAL_TOOL_EVIDENCE`
- `REQUIRED_TOOL_SUCCESS`
- `VISUAL_EVIDENCE`
- `SOURCE_CONTENT_EVIDENCE`
- `FILE_CONTENT_EVIDENCE`
- `CODE_ACTION_EVIDENCE`
- `PYTHON_TARGET_VERIFIED`

## Побудова контракту

Contract детерміновано будується з уже визначеного `TaskIntent`, explicit
required tools і concrete goal text. Model prose не додає критерії і не може
перевести criterion у PASSED.

### PUBLIC_WEB

Успішний `web.search` може задовольнити загальну вимогу мати реальний tool
evidence, але **не** `SOURCE_CONTENT_EVIDENCE`. Для source-content criterion
потрібен успішний `web.read`, `http.get` або `http.json`.

Отже search snippet сам по собі не дозволяє DONE.

### FILE_INSPECTION

Якщо goal реально просить прочитати/відкрити вміст файла, додається
`FILE_CONTENT_EVIDENCE`, який проходить лише після `file.read`.

Listing-only goal не отримує штучної вимоги `file.read`.

### CODE_WORK

Одного `context.snapshot` недостатньо. `CODE_ACTION_EVIDENCE` вимагає
успішний MUTATING або EXECUTABLE tool result.

Якщо успішний `file.write/file.patch` змінює `.py`, створюється
`PYTHON_TARGET_VERIFIED` для конкретного normalized target. Його може
підтвердити:

- `python.syntax_check` того самого script;
- `python.run` того самого script;
- повний `python.tests`, якщо test scope реально містить target.

Ця verification має `INDEPENDENT_TOOL_RESULT`.

Повторна зміна того самого Python target повертає criterion у PENDING і видаляє
старий verification evidence.

## Completion gate

`AgentController.interpretDone` перевіряє mandatory criteria до DONE.

Plain reply також не може обійти contract. Для PUBLIC_WEB plain-reply shortcut
працює тільки коли mandatory Goal Contract criteria вже PASSED.

Старі completion gates — pending Python verification, required tools,
ContextKernel unknown outcome, visual evidence і summary consistency — не
видаляються; Goal Contract додає типізований шар поверх них.

## Authority invariants

Goal Contract:

- не виконує tools;
- не надає permission/approval;
- не змінює ToolGate;
- не активує Constitution;
- не надає Laya execution authority;
- не приймає model prose як `CriterionEvidence`;
- не називає typed operational coverage повним semantic proof.

## Backward compatibility

`TaskState.goalContract` має default `GoalContract()`. Старий session JSON
без цього поля читається як `coverage=NONE`, без ретроактивного вигадування
criteria/evidence.

## Diagnostics

Новий блок:

```text
[GOAL_CONTRACT]
present=true
coverage=TYPED_OPERATIONAL_V1
criteria=
mandatory=
passed=
independent_passed=
pending=
all_mandatory_passed=
coverage_is_full_semantic_proof=false
```

## Acceptance

Для canary потрібні:

1. exact-head Android CI green;
2. owner-signed in-place install;
3. реальна operational task створює Goal Contract;
4. preflight-only evidence не закриває CODE_WORK;
5. Python mutation створює pending target criterion;
6. independent syntax/test verification переводить target criterion у PASSED;
7. diagnostic показує contract counters;
8. старі Fractal/Causal/Prediction counters та authority invariants не регресують.
