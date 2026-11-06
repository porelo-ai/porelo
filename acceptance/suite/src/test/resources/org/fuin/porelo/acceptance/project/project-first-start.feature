@slow
Feature: The first start finds what a repository needs

    A task started at a terminal in a checkout with no project.yml asks the installed plugins, says what they found and
    what it opens, and takes it only at a yes. The stub plugin answers from answers.json beside it.

    @first-start-answers
    Scenario: a yes writes project.yml; the same file from the same upstream is that project, and nothing else is
        Given a script runs:
            """
            set -eu
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
            ],"unplaced":[{"file":"Dockerfile","line":3,"note":"a container is built here"}]}}
            JSON
            """
        Then it exits zero
        When a script runs:
            """
            cd ~/first-start-probe/shop
            porelo task start probe --agent stub --yes --dry-run 2>&1 | grep -o 'nothing written and nothing opened'
            test -e project.yml && echo 'a file was written' || echo 'no file yet'
            """
        Then its output contains "nothing written and nothing opened"
        And its output contains "no file yet"
        Given a terminal on the machine
        When I run "cd ~/first-start-probe/shop && porelo task start probe --agent stub --detach --clearance deny --notes"
        Then the terminal shows "Dockerfile:3: a container is built here"
        And the terminal shows "nexus.first-start.example  <- only this repository names it"
        And the terminal shows "[y/N]"
        When I type "y"
        Then the terminal shows "shop-project"
        When a script runs:
            """
            base=~/first-start-probe
            cd "$base/shop"
            grep -c -E 'name: "?shop-project"?$' project.yml | sed 's/^/named: /'
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
            rm -rf ~/first-start-probe
            """
        Then its output contains "named: 1"
        And its output contains "commits of it: 0"
        And its output contains "a second clone: 1"
        And its output contains "a worktree: 1"
        And its output contains "another origin: already has a project 'shop-project'"
        And its output contains "a changed file: nothing written and nothing opened"

    @first-start-answers
    Scenario: a plugin's fetched lines are shown whole before the question, and a dry run fetches nothing
        Given a script runs:
            """
            set -eu
            base=~/first-start-resolve
            rm -rf "$base"
            mkdir -p "$base"
            git init -q --initial-branch=main "$base/tool"
            git -C "$base/tool" remote add origin https://forge.example/acme/tool.git
            cat > "${XDG_DATA_HOME:-$HOME/.local/share}/porelo/ecosystems/answers.json" <<'JSON'
            {"detect":{"findings":[
              {"part":"TOOLS","key":"java.version","value":"25","file":"pom.xml","line":1,"origin":"CHECKOUT","unknown":false,"note":""}
            ],"unplaced":[]},
             "resolve":{"findings":[
              {"part":"TOOLS","key":"run.java","value":"RUN echo fetched-by-the-stub","file":"pom.xml","line":1,"origin":"CHECKOUT","unknown":false,"note":""},
              {"part":"TOOLS","key":"env.STUB_HOME","value":"/opt/stub","file":"pom.xml","line":1,"origin":"CHECKOUT","unknown":false,"note":""}
            ]}}
            JSON
            """
        Then it exits zero
        When a script runs:
            """
            cd ~/first-start-resolve/tool
            porelo task start probe --agent stub --dry-run 2>&1 | grep -c 'fetched-by-the-stub' | sed 's/^/fetched at a dry run: /'
            """
        Then its output contains "fetched at a dry run: 0"
        Given a terminal on the machine
        When I run "cd ~/first-start-resolve/tool && porelo task start probe --agent stub --detach --clearance deny"
        Then the terminal shows "image line  RUN echo fetched-by-the-stub"
        And the terminal shows "image env  STUB_HOME=/opt/stub"
        # Nothing here builds a container or names a host of its own, so a yes is the default.
        And the terminal shows "[Y/n]"
        When I type "n"
        Then the terminal shows "nothing kept"
        When a script runs:
            """
            cd ~/first-start-resolve/tool
            test -e project.yml && echo 'a file was written' || echo 'no file after a no'
            porelo task remove porelo-default-probe --force > /dev/null 2>&1 || true
            rm -rf ~/first-start-resolve
            """
        Then its output contains "no file after a no"
