# Citation checking for skills that pin their claims to upstream spans
# (see scripts/skill_citations.py). `tool` is the CLI for the dev shell;
# `checks` holds one `citations-<skill>` check per skill with a
# citations.lock.json.
#
# A check fetches each cited file by raw URL at the locked commit, as a
# fixed-output derivation keyed by the lock's file hash, so it runs in the
# sandbox without network and fails if the lock, the footnotes or the
# anchors disagree.
{ pkgs, skillsDir }:
let
  inherit (pkgs) lib;

  script = ../scripts/skill_citations.py;

  tool = pkgs.writeShellApplication {
    name = "skill-citations";
    runtimeInputs = [
      pkgs.git
      pkgs.python3
    ];
    text = ''exec python3 ${script} "$@"'';
  };

  citedSkills = lib.filterAttrs (
    name: type: type == "directory" && builtins.pathExists (skillsDir + "/${name}/citations.lock.json")
  ) (builtins.readDir skillsDir);

  checkFor =
    name:
    let
      skill = skillsDir + "/${name}";
      lock = lib.importJSON (skill + "/citations.lock.json");
      upstream = pkgs.linkFarm "${name}-cited-upstream" (
        lib.mapAttrsToList (path: sha256: {
          name = path;
          path = pkgs.fetchurl {
            url = "https://raw.githubusercontent.com/${lock.repo}/${lock.commit}/${path}";
            inherit sha256;
          };
        }) lock.files
      );
    in
    pkgs.runCommand "citations-${name}" { nativeBuildInputs = [ pkgs.python3 ]; } ''
      python3 ${script} verify ${skill} --source ${upstream}
      touch $out
    '';
in
{
  inherit tool;
  checks = lib.mapAttrs' (name: _: lib.nameValuePair "citations-${name}" (checkFor name)) citedSkills;
}
