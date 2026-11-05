@slow
Feature: The first start finds what a repository needs

    A task started at a terminal in a checkout with no project.yml asks the installed plugins, says what they found and
    what it opens, and takes it only at a yes. The stub plugin answers from answers.json beside it.

    Scenario: a yes writes project.yml and the second start finds it, and --yes before it wrote nothing
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
            cd ~/first-start-probe/shop
            grep -c 'name: "shop-project"' project.yml | sed 's/^/named: /'
            git log --oneline -- project.yml | wc -l | sed 's/^/commits of it: /'
            porelo task start second --agent stub --dry-run 2>&1 | grep -c "'shop-project'" | sed 's/^/second start: /'
            porelo task remove porelo-shop-project-probe --force > /dev/null 2>&1
            porelo project remove shop-project > /dev/null 2>&1
            rm -f "${XDG_DATA_HOME:-$HOME/.local/share}/porelo/ecosystems/answers.json"
            rm -rf ~/first-start-probe
            """
        Then its output contains "named: 1"
        And its output contains "commits of it: 0"
        And its output contains "second start: 1"
