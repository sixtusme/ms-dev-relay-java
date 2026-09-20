# Maestro AI Architecture

## Purpose

Maestro/Sixai is an autonomous software-development agent platform integrated with Jira.

Its purpose is to understand development tasks, reason about them, retrieve relevant knowledge, use controlled tools, modify and validate software, diagnose failures, and coordinate long-running development workflows with human approval when required.

The architecture must remain modular, extensible, observable, and permission-controlled.

---

## Core architecture

```text
Jira
  │
  ▼
Maestro Orchestrator
  │
  ▼
Router / Planner
  │
  ├── Skills
  ├── Knowledge
  └── Context / Memory
  │
  ▼
Agent Runtime
  │
  ▼
LLM
  │
  ├── Tool calls
  │
  ▼
Permission / Guardrails
  │
  ▼
Tools
  │
  ├── GitHub
  ├── Jira
  ├── Jenkins
  ├── Infrastructure
  └── Other systems
```

The `AgentRuntime` owns the agent/tool execution loop.

Agents must not implement their own LLM → tool → LLM loop.

---

## Architectural boundaries

### Agents

Agents perform reasoning and decision-making.

Examples:

* `RouterAgent`
* `PlannerAgent`
* `CoderAgent`
* `ReviewerAgent`
* `DiagnosticianAgent`

Agents decide **what should happen**.

Agents must not contain direct HTTP/API/infrastructure implementations.

---

### Tools

Tools perform concrete operations.

Examples:

```text
github.search_code
github.read_file
github.write_file
github.create_branch
github.commit
github.create_pr

jira.read_issue
jira.comment
jira.transition

jenkins.start_build
jenkins.get_console

infra.container_logs
```

Tools decide **how an operation is executed**.

All tool execution must pass through permission and guardrail checks.

The LLM must never execute arbitrary shell commands or directly access infrastructure.

---

### Skills

Skills are reusable instructions and domain knowledge stored primarily as Markdown.

Examples:

```text
spring-boot.md
java.md
maven.md
github.md
testing.md
docker.md
security.md
```

Skills describe:

* when they apply
* relevant knowledge
* recommended procedures
* constraints
* required tools

Skills do not execute code.

Only relevant skills should be loaded for an execution.

---

### Knowledge

Knowledge contains persistent technical information about systems, repositories, architecture, services, documentation, and relationships.

Knowledge should be retrieved on demand.

Do not place an entire knowledge base or repository into an LLM prompt.

The long-term architecture is:

```text
Documents → chunks → semantic retrieval
                         +
                    knowledge graph
```

---

### Memory

Memory stores execution state and history.

It is different from Knowledge.

Memory includes:

* execution state
* current step
* tool calls
* results
* decisions
* errors
* approvals
* resumable workflow state

Long-running executions must be persistable and resumable.

---

## LLM abstraction

Agents must depend on:

```java
LlmClient
```

not on Ollama, OpenAI, or another provider directly.

The initial implementation uses an OpenAI-compatible API.

Changing the LLM provider must not require changes to agents or tools.

---

## Tool abstraction

Every tool implements:

```java
Tool
```

and provides:

* unique ID
* description
* input schema
* risk level
* execution implementation

Risk levels:

```text
READ
WRITE
DESTRUCTIVE
PRODUCTION
```

Tool execution follows:

```text
LLM
 ↓
Tool Call
 ↓
Resolve Tool
 ↓
Validate Arguments
 ↓
Check Permissions
 ↓
Apply Guardrails
 ↓
Execute
 ↓
Sanitize Result
 ↓
Return Result to Agent
```

---

## Permissions and safety

LLM intent is not authorization.

A model requesting a tool does not mean the action is allowed.

Every operation must be validated before execution.

Production and destructive operations must never be implicitly enabled.

Existing guardrails must remain active, including:

* secret redaction
* prompt shielding
* denied paths
* change-set validation
* production protection
* dry-run support
* human approval

Never expose credentials, tokens, passwords, or secrets to the LLM.

---

## Agent execution

The runtime follows this general loop:

```text
create execution
      ↓
load context
      ↓
select agent
      ↓
load skills / knowledge
      ↓
call LLM
      ↓
tool call?
   ┌──┴──┐
   │     │
  yes    no
   │     │
execute  finish
 tool
   │
add result to context
   │
   └──────→ call LLM again
```

Every execution must have:

* an `executionId`
* bounded steps
* persistent state
* observable actions
* explicit final status

The runtime must support pausing and resuming executions.

---

## Main agents

### Router

Determines the task intent and initial execution path.

### Planner

Understands the task, retrieves context, inspects repositories, selects relevant skills, and creates an implementation plan.

Planner should normally use READ tools only.

### Coder

Implements the approved plan using controlled WRITE tools.

### Reviewer

Reviews changes, tests, diffs, and implementation quality.

Reviewer should not modify code.

### Diagnostician

Analyses build, test, deployment, and infrastructure failures and proposes corrective actions.

Production auto-correction is disabled.

---

## Repository structure

Recommended structure:

```text
src/main/java/.../ai/
├── agent/
├── orchestration/
├── tools/
├── skills/
├── knowledge/
├── memory/
└── llm/

src/main/resources/ai/
├── skills/
├── knowledge/
├── prompts/
└── architecture.md
```

Keep domain/infrastructure implementations outside the agent layer.

---

## Extensibility

Adding a new capability should normally require only:

### New tool

```java
@Component
public class MyTool implements Tool {
}
```

### New agent

```java
@Component
public class MyAgent implements LlmAgent {
}
```

### New skill

```text
src/main/resources/ai/skills/my-skill.md
```

The core runtime should not need modification for ordinary extensions.

---

## Existing system compatibility

This architecture is an evolution of the existing `ms-dev-relay-java` application.

Do not rewrite existing integrations unnecessarily.

Preserve:

* Jira integration
* GitHub integration
* Jenkins integration
* Harbor integration
* infrastructure diagnostics
* routing
* idempotency
* correction loop
* approval workflow
* reports
* existing LLM configuration
* existing security restrictions

Prefer incremental migration over replacement.

---

## Design rules

1. Agents reason; tools execute.
2. Runtime orchestrates.
3. LLMs never receive unrestricted infrastructure access.
4. Authorization is enforced outside the LLM.
5. Skills provide instructions, not execution.
6. Knowledge is retrieved, not dumped into prompts.
7. Memory stores execution state, not general knowledge.
8. External systems are accessed through tools/clients.
9. Long-running executions must be resumable.
10. New capabilities should be added through extension points rather than modifying the core runtime.
11. Keep abstractions small and implementation-focused.
12. Do not introduce infrastructure complexity before it is needed.
13. Preserve existing functionality while migrating toward this architecture.
14. Every autonomous write operation must remain auditable and permission-controlled.
15. Never bypass existing guardrails to make an agent workflow work.
