#!/usr/bin/env bash
set -euo pipefail

REMOTE="${1:-origin}"

declare -A SNAPSHOT=(
  ["snapshot/2026-09-26-main"]="9934294dd71df150b92505e3b9054574de9db621"
  ["snapshot/2026-09-26-tip"]="21ace87ee9472c5a3824bb406f09c41070c742e4"
  ["snapshot/pr-1"]="5a9f0cff0069aa40a506655526bc2800ba21a7fd"
  ["snapshot/pr-2"]="e7a3a386b807fc20b9a5cb87b8456a2765d10538"
  ["snapshot/pr-3"]="a5c929f3eadb663db2c0ae8750c9b3d759069f4c"
  ["snapshot/pr-4"]="7f7191a8d36014e549169f265763e47eceb37bc8"
  ["snapshot/pr-5"]="eac69cd3eb2df0680c7634135096d0b0225e4c2d"
  ["snapshot/pr-6"]="5c7c2c898a40f56aad16a876e0b366c9ba25630d"
  ["snapshot/pr-7"]="3618a498880c0455f68cb0064f766b957f579637"
  ["snapshot/pr-8"]="989973024557a5149fb077c9f941c457288be4c7"
  ["snapshot/pr-9"]="684e9151b62e1db01bf2f8a31b8f6cac986cea97"
  ["snapshot/pr-13"]="5d0de7fef6234830dbd39d3b157733ab04162b2a"
  ["snapshot/pr-23"]="695a4dc379cc143ef20bc53aa24e6207bc8e9fc1"
  ["snapshot/pr-50"]="ce8158aa7013a1bee1474439a5bd25a1405a78ee"
  ["snapshot/pr-52"]="98a49fe021d332517a06879dc036eca032b2aa4c"
  ["snapshot/pr-53"]="939faa0c8ec69bffb5553708d48ec9b664568cea"
  ["snapshot/pr-58"]="bc165227cd629b7162c2336b480e79aba3a17fd2"
  ["snapshot/pr-59"]="32e8f9875b374996e8239645fa74306ed0a76ad9"
  ["snapshot/pr-61"]="28acadee3478b900042026bd7a32bff7f9636846"
  ["snapshot/pr-65"]="4a12319b154be2fb1514618a7ead2c7ec8c74b72"
  ["snapshot/pr-68"]="909d7135a6eba7547b33d36fbedd05393658734c"
  ["snapshot/pr-69"]="d9fd09393a774a59b1a11c2af0b47380758d3b95"
  ["snapshot/pr-71"]="b04fba74fc95a36d8c04955adbc449407cc0a458"
  ["snapshot/pr-72"]="1bafb8be8174fe9fac68cb821fa74719d0fb8314"
  ["snapshot/pr-73"]="81568249e62aa03636de7025fff31d47dea52052"
  ["snapshot/pr-75"]="47c616ba0ef21ed84d03f263f3cfc7490b9138da"
  ["snapshot/pr-77"]="463b2f30a4a973d223455a8185b0ef8f128af1fd"
  ["snapshot/pr-79"]="634d59648049cec61fcae2fec381133df004291f"
  ["snapshot/pr-80"]="10f67aad1e9c4187c1344a5cb72b5bbd88efc0a7"
  ["snapshot/pr-82"]="d52bae32edb98a628cf533098198e834dfdf5749"
  ["snapshot/pr-85"]="56dc4b0fba78a0a302760c7dec10d3779d20279e"
  ["snapshot/pr-87"]="0c737e330d95d56681c0ca27e479b91456f7d585"
  ["snapshot/pr-88"]="19027383c0314ea88ac0bee0b64d8c900252ab6a"
  ["snapshot/pr-89"]="aa85e891b4b04bbde455a5f0b52d5ae739cf39ca"
  ["snapshot/pr-90"]="45c9765de105aa5481192638322349ca680afb12"
  ["snapshot/pr-91"]="581406ef1bcccabbe299af0e0c23ea9a141d7c7f"
  ["snapshot/pr-97"]="7b4ee06a1f1033e04b5d69a136d7c2f3a26b7a5f"
)

git fetch "$REMOTE" --prune

refs=()
for tag in "${!SNAPSHOT[@]}"; do
  sha="${SNAPSHOT[$tag]}"
  git cat-file -e "${sha}^{commit}"
  existing="$(git rev-parse -q --verify "refs/tags/$tag" || true)"
  if [[ -n "$existing" && "$existing" != "$sha" ]]; then
    echo "REFUSE: $tag exists at $existing, expected $sha" >&2
    exit 2
  fi
  if [[ -z "$existing" ]]; then
    git tag "$tag" "$sha"
  fi
  refs+=("refs/tags/$tag")
done

git push --atomic "$REMOTE" "${refs[@]}"

echo "Snapshot tags:"
git ls-remote --tags "$REMOTE" 'snapshot/*'
