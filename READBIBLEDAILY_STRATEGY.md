# ReadBibleDaily — Project Strategy

## Identity

ReadBibleDaily is a long-lived fork of AndBible. AndBible remains the upstream technical base; ReadBibleDaily has its own product roadmap and may diverge where its goals require it.

Original AndBible documentation: https://docs.andbible.org/en/latest/

## Source-of-truth branches

- `upstream` = `AndBible/and-bible`.
- `origin` = ReadBibleDaily fork.
- `current-stable` = clean mirror of `upstream/current-stable` only. Never add ReadBibleDaily commits here. Update only with `git merge --ff-only upstream/current-stable`.
- `rbd-main` = permanent ReadBibleDaily product branch: upstream base + approved ReadBibleDaily extensions.
- `feature/...` and `fix/...` = short-lived branches created from `rbd-main`.
- `sync/andbible-<version>` = temporary branch used to integrate a newer AndBible base into `rbd-main`.
- Branches intended for upstream PRs must start from the upstream branch requested by AndBible and contain only generally useful changes, not ReadBibleDaily-specific product behavior.

Do not rewrite shared `rbd-main` history. Prefer explicit merge history for upstream synchronizations.

## Upstream synchronization

1. `git fetch upstream` and `git fetch origin`.
2. Fast-forward local `current-stable` from `upstream/current-stable` and push it to `origin/current-stable`.
3. Create `sync/andbible-<version>` from `rbd-main`.
4. Merge the updated `current-stable` into the sync branch.
5. Resolve conflicts conservatively: preserve upstream structure and keep ReadBibleDaily hooks as narrow as practical.
6. Run compile, focused automated tests, and manual smoke tests for affected flows.
7. Integrate the validated sync branch into `rbd-main`.

Never merge upstream updates directly into an in-progress feature branch unless there is a specific reason.

## Architecture rule

Keep ReadBibleDaily-specific code as isolated from upstream code as practical.

Prefer:

- new ReadBibleDaily-owned components over large edits to upstream classes;
- narrow integration points over duplicated upstream logic;
- existing AndBible services, navigation, rendering, storage abstractions, and UI patterns where they already fit;
- separate domain/model code for ReadBibleDaily behavior that is expected to grow;
- generic fixes that could benefit AndBible to remain independently upstreamable.

Avoid broad refactors while implementing a feature unless they are required for correctness.

## Development method

Before changing code, read and follow the repository's current:

- `AGENTS.md`
- `CLAUDE.md`
- `CONTRIBUTING.md`
- `.github/copilot-instructions.md`

Those files are authoritative for AndBible coding, testing, style, UI, and contribution conventions. This file adds ReadBibleDaily-specific product and Git rules.

ReadBibleDaily work must also follow this workflow:

1. Define one narrow behavior and acceptance criteria.
2. Inspect existing AndBible patterns before designing new infrastructure.
3. Implement the smallest coherent step.
4. Add focused tests first or alongside the change where practical.
5. Compile and run only the relevant regression tests after each step.
6. Perform manual UI verification for user-facing behavior.
7. Run an AI self-review and personally review the full diff.
8. Commit only the verified step.
9. Continue with the next step only from a clean tracked working tree.

Prefer several small, understandable commits over one large feature commit. Do not mix unrelated cleanup, refactoring, or generated files into feature commits.

## Product direction

ReadBibleDaily will extend the AndBible base in this order unless a dependency requires otherwise:

1. Establish and maintain the long-lived fork structure and clean upstream-sync process.
2. Evolve custom reading plans from the validated prototype to persistent plans with multiple days/passages and explicit user-defined reading schedules.
3. Support Orthodox Bible editions and additional Orthodox content such as prayer books and writings of the Holy Fathers, in multiple languages, subject to content licensing.
4. Add scheduled content events, including morning/evening prayers, Scripture reading-plan continuation, and communion-prayer flows based on weekday and time window.
5. Make distraction-free reading the default ReadBibleDaily experience, while retaining a deliberate way to access broader study tools.
6. Rebrand application identity, package/distribution identifiers, release pipeline, and user-facing attribution when ReadBibleDaily is ready to ship as a distinct application.

## Product behavior goals

The application should eventually support:

- user-defined reading plans: what to read, how much, over what period, and at what time of day;
- scheduled startup content based on weekday/time rules;
- Orthodox Scripture and devotional/patristic content in multiple languages;
- normal reuse of AndBible reading/navigation/rendering capabilities where appropriate;
- a distraction-free default experience.

Scheduling, persistence, content storage, and synchronization must be designed as explicit subsystems rather than accumulated as ad-hoc Activity logic.

## Upstream contribution policy

Upstream acceptance must not block the ReadBibleDaily roadmap.

When a ReadBibleDaily change is generic and useful to AndBible:

- reproduce it on a clean branch from the appropriate upstream base;
- keep the PR narrowly scoped;
- follow AndBible contribution rules independently of `rbd-main`;
- do not make ReadBibleDaily depend on that PR being merged.

If upstream later implements or accepts equivalent behavior, remove local duplication during a normal upstream synchronization.

## Safety rails for AI-assisted work

AI may analyze, implement, test, and review changes, but every change must remain understandable and reviewable by the project owner.

AI must not:

- place ReadBibleDaily changes on `current-stable`;
- stage unrelated or pre-existing untracked files;
- widen task scope without explicit approval;
- introduce persistence/schema/storage changes as a side effect of a UI task;
- rewrite large files when a local change is sufficient;
- claim tests or manual behavior that were not actually verified.

When uncertain about architecture or upstream behavior, stop at a buildable checkpoint and investigate before extending the change.
