{
  description = "xtdb-skills dev-shell skill set — an isolated sub-flake invoked at RUNTIME by the root devShell, never a root input. The skill sources live only in THIS flake's lock, so the root xtdb-skills stays a leaf with zero skill inputs and transitive consumers never drag the skill mesh in.";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    systems.url = "github:nix-systems/default";

    # `agent-skill-flake` is the builder library, not a skill — it provides
    # `mkDevshellSkillsFlake`. The combination below follows it so the
    # combiner runs from this one pin.
    agent-skill-flake = {
      url = "github:nhooey/agent-skill-flake";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    # Skill repositories. Each follows only nixpkgs, keeping its own
    # compatible agent-skill-flake.
    coding-agent-skills = {
      url = "github:nhooey/coding-agent-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    data-skills = {
      url = "github:nhooey/data-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    document-skills = {
      url = "github:nhooey/document-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    git-skills = {
      url = "github:nhooey/git-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    nix-skills = {
      url = "github:nhooey/nix-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    shell-skills = {
      url = "github:nhooey/shell-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    # skillspkgs' curated `authoring-with-git` combination (nix + humanizer +
    # anthropic skill-creation + superpowers + the whole git/GitHub pack),
    # surfaced through its own subdir flake so it stays re-composable as a
    # source. Its git-skills and nix-skills follow the inputs above, so the
    # combination and the direct sources install the same skills.
    skillspkgs-combinations = {
      url = "github:nhooey/skillspkgs?dir=sources/combinations";
      inputs = {
        nixpkgs.follows = "nixpkgs";
        agent-skill-flake.follows = "agent-skill-flake";
        git-skills.follows = "git-skills";
        nix-skills.follows = "nix-skills";
      };
    };

    writing-skills = {
      url = "github:nhooey/writing-skills";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      nixpkgs,
      agent-skill-flake,
      ...
    }@inputs:
    agent-skill-flake.lib.mkDevshellSkillsFlake {
      inherit nixpkgs;
      systems = import inputs.systems;
      name = "xtdb-skills-devshell";
      envName = "agent-skills-xtdb-skills-devshell";
      sources = [
        # Running as a headless session in a checkout, keeping the
        # scratchpad tidy, and the cross-repository plan's graph.
        {
          source = inputs.coding-agent-skills;
          skills = [
            "coding-agent-headless-in-checkout"
            "coding-agent-plans-as-graph"
            "coding-agent-scratchpad-scripts"
          ];
        }
        { source = inputs.data-skills; }
        { source = inputs.document-skills; }
        # Safe shell commands: named deletes, PIDs, no machine-wide changes.
        { source = inputs.shell-skills; }
        { source = inputs.skillspkgs-combinations.combinations.authoring-with-git; }
        { source = inputs.writing-skills; }
      ];
    };
}
