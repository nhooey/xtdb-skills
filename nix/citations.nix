# Citation checking for skills that pin their claims to upstream spans
# (see scripts/skill-citations.clj). `tool` is the CLI for the dev shell;
# `checks` holds one `citations-<skill>` check per skill with a
# citations.lock.json.
#
# A check fetches each cited file by raw URL at the locked commit, as a
# fixed-output derivation keyed by the lock's file hash, so it runs in the
# sandbox without network and fails if the lock, the footnotes or the
# anchors disagree. It runs against the whole repo, so a skill's observed
# footnotes can be checked against the eval examples they name.
{ pkgs, root }:
let
  inherit (pkgs) lib;

  script = ../scripts/skill-citations.clj;

  tool = pkgs.writeShellApplication {
    name = "skill-citations";
    runtimeInputs = [
      pkgs.babashka
      pkgs.git
    ];
    text = ''exec bb ${script} "$@"'';
  };

  skillsDir = root + "/skills";

  citedSkills = lib.filterAttrs (
    name: type: type == "directory" && builtins.pathExists (skillsDir + "/${name}/citations.lock.json")
  ) (builtins.readDir skillsDir);

  checkFor =
    name:
    let
      lock = lib.importJSON (skillsDir + "/${name}/citations.lock.json");
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
    pkgs.runCommand "citations-${name}" { nativeBuildInputs = [ pkgs.babashka ]; } ''
      bb ${script} verify ${root}/skills/${name} --source ${upstream}
      touch $out
    '';
in
{
  inherit tool;
  checks = lib.mapAttrs' (name: _: lib.nameValuePair "citations-${name}" (checkFor name)) citedSkills;
}
