@slow
Feature: The first start finds what a repository needs

    A task started at a terminal in a checkout with no project.yml asks the installed plugins, says what they found and
    what it opens, and takes it only at a yes. The stub plugin answers from answers.json beside it.

    Scenario: a yes writes project.yml; the same file from the same upstream is that project, and nothing else is
        Given a script runs:
            """
            base=~/first-start-probe
            rm -rf "$base"
            mkdir -p "$base"
            git init -q --bare --initial-branch=main "$base/shop.git"
            git init -q --initial-branch=main "$base/seed"
            git -C "$base/seed" -c user.name=T -c user.email=t@example.org commit -q --allow-empty -m 'first'
            git -C "$base/seed" push -q "$base/shop.git" HEAD:main
            git clone -q "$base/shop.git" "$base/shop"
            cat > "${XDG_DATA_HOME:-$HOME/.local/share}/porelo/ecosystems/answers.json" <<'JSON'
            {"detect":{"findings":[
              {"part":"TOOLS","key":"package.maven","value":"","file":"pom.xml","line":1,"origin":"CHECKOUT","unknown":false,"note":""},
              {"part":"RULES","key":"host.nexus.first-start.example","value":"yes","file":"pom.xml","line":2,"origin":"CHECKOUT","unknown":false,"note":""}
            ],"unplaced":[]}}
            JSON
            """
        When a script runs:
            """
            cd ~/first-start-probe/shop
            porelo task start probe --agent stub --yes --dry-run 2>&1 | grep -o 'nothing written and nothing opened'
            test -e project.yml && echo 'a file was written' || echo 'no file yet'
            """
        Then its output contains "nothing written and nothing opened"
        And its output contains "no file yet"
        Given a terminal on the machine
        When I run "cd ~/first-start-probe/shop && porelo task start probe --agent stub --detach --clearance deny"
        Then the terminal shows "nexus.first-start.example  <- only this repository names it"
        And the terminal shows "[y/N]"
        When I type "y"
        Then the terminal shows "shop-project"
        When a script runs:
            """
            base=~/first-start-probe
            cd "$base/shop"
            grep -c 'name: "shop-project"' project.yml | sed 's/^/named: /'
            git log --oneline -- project.yml | wc -l | sed 's/^/commits of it: /'
            git add project.yml
            git -c user.name=T -c user.email=t@example.org commit -q -m 'the project'
            git push -q origin HEAD:main
            git clone -q "$base/shop.git" "$base/clone"
            cd "$base/clone"
            porelo task start second --agent stub --no-input --dry-run 2>&1 | grep -q "shop-project" && echo 'a second clone: 1'
            cd "$base/shop"
            git worktree add -q "$base/worktree"
            cd "$base/worktree"
            porelo task start second --agent stub --no-input --dry-run 2>&1 | grep -q "shop-project" && echo 'a worktree: 1'
            git init -q --initial-branch=main "$base/stranger"
            cp "$base/shop/project.yml" "$base/stranger/"
            git -C "$base/stranger" remote add origin https://forge.example/stranger/thing.git
            cd "$base/stranger"
            porelo task start second --agent stub --no-input --dry-run 2>&1 | grep -o "already has a project 'shop-project'" | sed 's/^/another origin: /'
            cd "$base/clone"
            echo '# changed' >> project.yml
            porelo task start second --agent stub --no-input --dry-run 2>&1 | grep -o "nothing written and nothing opened" | sed 's/^/a changed file: /'
            cd "$base/shop"
            porelo task remove porelo-shop-project-probe --force > /dev/null 2>&1
            porelo project remove shop-project > /dev/null 2>&1
            rm -f "${XDG_DATA_HOME:-$HOME/.local/share}/porelo/ecosystems/answers.json"
            rm -rf ~/first-start-probe
            """
        Then its output contains "named: 1"
        And its output contains "commits of it: 0"
        And its output contains "a second clone: 1"
        And its output contains "a worktree: 1"
        And its output contains "another origin: already has a project 'shop-project'"
        And its output contains "a changed file: nothing written and nothing opened"
