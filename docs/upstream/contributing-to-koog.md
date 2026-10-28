# Contributing Kroog changes to upstream Koog

These guidelines record the agreed approach for contributing fixes and features from [Kroog](https://github.com/Kreoh/kroog) to [Koog](https://github.com/JetBrains/koog). Work through candidates one at a time, keeping each contribution small and independently reviewable.

Kroog should follow upstream implementations, retaining its additional features and fixes on top. This includes skills. Prefer a focused extension of upstream code over importing a fork-specific replacement.

## Check existing work first

Before preparing a contribution:

1. Refresh upstream `develop` and check whether the change is still missing.
2. Search GitHub issues, open PRs and the public Koog YouTrack project for the same problem.
3. Record matching links, issue status, PR opening date, last public comment and review activity. Read the discussion and unresolved review requests.
4. Pin the upstream baseline, relevant PR heads and Kroog source commits so comparisons can be reproduced.

Use an existing matching issue. A related title alone does not establish that it describes the same failure. Do not assume an inactive PR was ignored because it lacks an issue; check its actual links and review history.

## Compare an existing PR with Kroog

Reproduce the problem on upstream, then run equivalent focused cases against the existing PR and Kroog. Where possible, apply the PR's unchanged patch to the same upstream baseline and record that method explicitly.

Compare demonstrated behaviour, including relevant streaming, replay, cancellation and failure cases. Inspect public API compatibility, dependencies and platform implications. Record what each implementation fixes and what remains broken. Patch size and existing production use do not by themselves establish that Kroog's implementation is better.

Choose the smallest useful contribution:

- Support the existing PR if it already solves the problem.
- Contribute missing tests or a small correction if it is close.
- Propose a separate replacement only when there is a concrete reason supplementation is unsuitable, explaining the overlap and preserving attribution.

Avoid opening another overlapping PR by default. If several PRs address the same issue, propose consolidation with their authors and maintainers.

## Issues need a short reproduction

If no matching issue exists, prepare a concise GitHub issue draft containing:

- The concrete failure and affected upstream revision or version.
- A minimal reproducer, preferably runnable without credentials.
- Expected and actual behaviour, including the relevant error.
- A direct link to official documentation establishing the expected support or behaviour.

Show the user the draft before publishing the issue. Publish only after approval. Keep the text short and reproducible rather than adding a long account of our investigation.

For existing issues and PRs, prepare comments around concrete evidence and a proposed next step. External comments and submissions require user authorisation; preserve authorisation already given for the current action rather than repeatedly asking for it.

## Prepare a minimal patch

Create an isolated contribution branch or worktree from current upstream `develop`; target upstream `develop` with the PR. For a supplement to an existing PR, use its agreed branch or an explicitly documented baseline.

Cherry-pick a Kroog commit when it contains only the relevant change and applies cleanly. Otherwise extract the necessary hunks and tests. Preserve original attribution and link the source commits. Do not bring unrelated release settings, branding, model additions, compatibility layers or architectural changes into a small fix.

Keep one concrete problem per PR. Retain upstream layout and APIs where possible. Separate adjacent improvements when they can stand alone, including individual skills fixes.

Prove the regression fails on upstream and passes with the proposed patch. Run the narrowest relevant tests and required checks. Follow the repository's JVM-only validation rules unless cross-platform work is explicitly requested. Record untested platforms, API compatibility concerns and any checks that could not run. Local mocked tests, live-provider tests and production experience are different kinds of evidence; describe each accurately.

## Explain production use through Kroog

When opening a PR for a fix already included and working in production applications through Kroog, say so explicitly. This gives maintainers useful evidence that the underlying fix has been exercised outside the new regression test.

Suggested wording:

> This fix is already included in [Kroog](https://github.com/Kreoh/kroog) and is working in production applications using Kroog. This PR adapts that implementation into a minimal upstream patch. Original implementation: [source commit](SOURCE_COMMIT_URL).

Link the relevant commit and, when known, the Kroog release containing it. Briefly name the exercised behaviour when it helps reviewers. Do not invent application names, deployment dates, release versions, traffic figures or claims that every edge case has been exercised.

If the upstream patch differs from the deployed implementation, explain the relevant difference and distinguish production experience with the Kroog implementation from local validation of the extracted patch. If production use of a particular change is not established, describe only the evidence available for that change.

Keep the rest of the PR description concise: the failure, resulting behaviour, focused validation and a link to the matching issue. Use a closing reference when the patch resolves that issue. Retain the other contributors' attribution when adapting their work.

## Keep the local record current

Update the local contribution plan after each investigation or external action. Preserve:

- Candidate scope and the decision to support, supplement or replace existing work.
- Issue, PR, documentation and source-commit links.
- Exact comparison revisions, contribution branch and resulting commits.
- Reproduction, test results, compatibility findings and remaining gaps.
- Relevant production-use evidence and any difference from the upstream patch.
- Drafts, approvals, publication status, reviewer feedback and next steps.

Record submission, merge and release as separate milestones. Keep enough information to write a later summary without reconstructing the conversation.

The working inventory is `.codex/plans/upstream-contributions.md`. It may be locally ignored by Git; these reusable guidelines belong in the repository documentation. Updating guidance does not itself authorise a commit, push or GitHub publication. Follow the active workflow's commit and publication rules.
