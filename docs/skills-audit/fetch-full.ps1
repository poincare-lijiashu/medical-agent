# Fetch the full reference files of the audited skills (not just SKILL.md).
# Source repos are recorded in ../manifest.json.
$ErrorActionPreference = 'Stop'
$base = Join-Path $PSScriptRoot 'full'

$items = @(
  '01-frontend-design-direction|https://raw.githubusercontent.com/affaan-m/ECC/main/skills/frontend-design-direction/scripts/check-evidence.js|scripts/check-evidence.js',
  '04-design-review|https://raw.githubusercontent.com/aictrl-dev/skills/main/skills/design-review/reference/rubric.md|reference/rubric.md',
  '05-shadcn|https://raw.githubusercontent.com/shadcn-ui/ui/main/skills/shadcn/design-system.md|design-system.md',
  '05-shadcn|https://raw.githubusercontent.com/shadcn-ui/ui/main/skills/shadcn/rules/styling.md|rules/styling.md',
  '05-shadcn|https://raw.githubusercontent.com/shadcn-ui/ui/main/skills/shadcn/rules/composition.md|rules/composition.md',
  '05-shadcn|https://raw.githubusercontent.com/shadcn-ui/ui/main/skills/shadcn/rules/forms.md|rules/forms.md',
  '05-shadcn|https://raw.githubusercontent.com/shadcn-ui/ui/main/skills/shadcn/rules/base-vs-radix.md|rules/base-vs-radix.md',
  '05-shadcn|https://raw.githubusercontent.com/shadcn-ui/ui/main/skills/shadcn/rules/icons.md|rules/icons.md',
  '06-frontend-ui-engineering|https://raw.githubusercontent.com/addyosmani/agent-skills/main/references/accessibility-checklist.md|references/accessibility-checklist.md',
  '07-reference-design-contract|https://raw.githubusercontent.com/nexu-io/open-design/main/skills/reference-design-contract/references/checklist.md|references/checklist.md',
  '08-taste-skill|https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/skills/taste-skill/references/layout.md|references/layout.md',
  '08-taste-skill|https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/skills/taste-skill/references/type.md|references/type.md',
  '08-taste-skill|https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/skills/taste-skill/references/colour.md|references/colour.md',
  '08-taste-skill|https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/skills/taste-skill/references/motion.md|references/motion.md',
  '08-taste-skill|https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/skills/taste-skill/references/critique.md|references/critique.md',
  '08-taste-skill|https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/skills/taste-skill/references/tells.md|references/tells.md',
  '08-taste-skill|https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/skills/taste-skill/references/copy.md|references/copy.md',
  '08-taste-skill|https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/skills/taste-skill/references/imagery.md|references/imagery.md',
  '08-taste-skill|https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/skills/taste-skill/references/app-screens.md|references/app-screens.md',
  '08-taste-skill|https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/skills/taste-skill/references/research.md|references/research.md',
  '08-taste-skill|https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/skills/taste-skill/assets/app.css|assets/app.css',
  '08-taste-skill|https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/skills/taste-skill/assets/base.css|assets/base.css',
  '08-taste-skill|https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/skills/taste-skill/README.md|README.md',
  '09-impeccable|https://raw.githubusercontent.com/pbakaus/impeccable/main/.agent/skills/impeccable/reference/craft-floor.md|reference/craft-floor.md',
  '09-impeccable|https://raw.githubusercontent.com/pbakaus/impeccable/main/.agent/skills/impeccable/reference/new-work.md|reference/new-work.md',
  '09-impeccable|https://raw.githubusercontent.com/pbakaus/impeccable/main/.agent/skills/impeccable/reference/polish.md|reference/polish.md',
  '09-impeccable|https://raw.githubusercontent.com/pbakaus/impeccable/main/.agent/skills/impeccable/reference/typeset.md|reference/typeset.md',
  '09-impeccable|https://raw.githubusercontent.com/pbakaus/impeccable/main/.agent/skills/impeccable/reference/layout.md|reference/layout.md',
  '09-impeccable|https://raw.githubusercontent.com/pbakaus/impeccable/main/.agent/skills/impeccable/reference/animate.md|reference/animate.md',
  '09-impeccable|https://raw.githubusercontent.com/pbakaus/impeccable/main/.agent/skills/impeccable/reference/component-review.md|reference/component-review.md',
  '09-impeccable|https://raw.githubusercontent.com/pbakaus/impeccable/main/.agent/skills/impeccable/reference/audit.md|reference/audit.md',
  '09-impeccable|https://raw.githubusercontent.com/pbakaus/impeccable/main/.agent/skills/impeccable/reference/harden.md|reference/harden.md',
  '09-impeccable|https://raw.githubusercontent.com/pbakaus/impeccable/main/.agent/skills/impeccable/reference/critique.md|reference/critique.md',
  '12-prototype|https://raw.githubusercontent.com/mattpocock/skills/main/skills/engineering/prototype/LOGIC.md|LOGIC.md',
  '12-prototype|https://raw.githubusercontent.com/mattpocock/skills/main/skills/engineering/prototype/UI.md|UI.md',
  '03-ui-ux-pro-max|https://raw.githubusercontent.com/nextlevelbuilder/ui-ux-pro-max-skill/main/.claude/skills/ui-ux-pro-max/references/quick-reference.md|references/quick-reference.md',
  '03-ui-ux-pro-max|https://raw.githubusercontent.com/nextlevelbuilder/ui-ux-pro-max-skill/main/.claude/skills/ui-ux-pro-max/references/pro-rules.md|references/pro-rules.md',
  '03-ui-ux-pro-max|https://raw.githubusercontent.com/nextlevelbuilder/ui-ux-pro-max-skill/main/.claude/skills/ui-ux-pro-max/data/ux-guidelines.csv|data/ux-guidelines.csv',
  '03-ui-ux-pro-max|https://raw.githubusercontent.com/nextlevelbuilder/ui-ux-pro-max-skill/main/.claude/skills/ui-ux-pro-max/data/colors.csv|data/colors.csv',
  '03-ui-ux-pro-max|https://raw.githubusercontent.com/nextlevelbuilder/ui-ux-pro-max-skill/main/.claude/skills/ui-ux-pro-max/data/typography.csv|data/typography.csv',
  '03-ui-ux-pro-max|https://raw.githubusercontent.com/nextlevelbuilder/ui-ux-pro-max-skill/main/.claude/skills/ui-ux-pro-max/data/motion.csv|data/motion.csv',
  '03-ui-ux-pro-max|https://raw.githubusercontent.com/nextlevelbuilder/ui-ux-pro-max-skill/main/.claude/skills/ui-ux-pro-max/data/charts.csv|data/charts.csv',
  '17-web-design-guidelines|https://raw.githubusercontent.com/vercel-labs/web-interface-guidelines/main/command.md|command.md'
)

foreach ($item in $items) {
  $parts = $item.Split('|')
  $slug = $parts[0]
  $url = $parts[1]
  $rel = $parts[2]
  $dir = Join-Path $base (Join-Path $slug (Split-Path -Parent $rel))
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $out = Join-Path $base (Join-Path $slug $rel)
  Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing -TimeoutSec 60
  Write-Output ("OK {0} -> {1} bytes" -f $rel, (Get-Item $out).Length)
}
