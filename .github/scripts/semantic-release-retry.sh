#!/usr/bin/env bash
# Runs semantic-release (the command comes in as the arguments) and tries again when GitHub rejects a push half way.
#
# semantic-release pushes the tag, then its notes ref, then creates the GitHub release and publishes. When GitHub
# rejects the notes push ("! [remote rejected] refs/notes/semantic-release-vX (failed)", seen 2026-11-12) the run stops
# with the tag pushed but no release, and a plain re-run takes that tag as already released and publishes nothing.
# Such a tag on HEAD (no GitHub release for it) is removed here, so the next try releases the same version again.
# A tag that has a GitHub release is never touched: then a later step failed, and the run fails as it is.
set -uo pipefail

attempts=3
status=1

export GH_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
remote="https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"

for attempt in $(seq 1 "$attempts"); do
  if "$@"; then
    exit 0
  fi
  status=$?

  echo "::warning::semantic-release failed (attempt $attempt/$attempts, exit $status)"

  git fetch --quiet --force "$remote" 'refs/tags/*:refs/tags/*' || true

  removed=0
  for tag in $(git tag --points-at HEAD); do
    if gh release view "$tag" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
      continue
    fi

    echo "Removing $tag: it was pushed, but its release was never created"
    git push --quiet "$remote" ":refs/tags/$tag" || true
    git push --quiet "$remote" ":refs/notes/semantic-release-$tag" 2>/dev/null || true
    git tag -d "$tag" >/dev/null 2>&1 || true
    git update-ref -d "refs/notes/semantic-release-$tag" 2>/dev/null || true
    removed=$((removed + 1))
  done

  if [ "$removed" -eq 0 ]; then
    echo "::error::No half-made release to clean up, not retrying"
    exit "$status"
  fi

  if [ "$attempt" -lt "$attempts" ]; then
    sleep $((attempt * 20))
  fi
done

exit "$status"
