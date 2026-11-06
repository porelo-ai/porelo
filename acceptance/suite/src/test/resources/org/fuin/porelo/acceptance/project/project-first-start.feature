@slow
Feature: The first start finds what a repository needs

    A task started at a terminal in a checkout with no porelo.yml asks the installed plugins, says what they found and
    what it opens, and takes it only at a yes. The stub plugin answers from answers.json beside it.

    @first-start-answers
    Scenario: a yes writes porelo.yml, keeps the repository in default and allows what it asks, so the next start asks nothing
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
            test -e porelo.yml && echo 'a file was written' || echo 'no file yet'
            """
        Then its output contains "nothing written and nothing opened"
        And its output contains "no file yet"
        Given a terminal on the machine
        When I run "cd ~/first-start-probe/shop && porelo task start probe --agent stub --detach --clearance deny --notes"
        Then the terminal shows "Dockerfile:3: a container is built here"
        And the terminal shows "nexus.first-start.example  <- only this repository names it"
        And the terminal shows "[y/N]"
        When I type "y"
        Then the terminal shows "'default'"
        When a script runs:
            """
            cd ~/first-start-probe/shop
            grep -c 'nexus.first-start.example' porelo.yml | sed 's/^/the host in it: /'
            test -e project.yml && echo 'a project.yml too' || echo 'no project.yml'
            git log --oneline -- porelo.yml | wc -l | sed 's/^/commits of it: /'
            porelo task start second --agent stub --no-input --dry-run > "$HOME/first-start-probe/second.out" 2>&1 || true
            grep -c -E "^project +'?default" "$HOME/first-start-probe/second.out" | sed 's/^/in default again: /'
            grep -c 'not allowed on this machine yet' "$HOME/first-start-probe/second.out" | sed 's/^/asked again: /'
            porelo task remove porelo-default-probe --force > /dev/null 2>&1 || true
            rm -rf ~/first-start-probe
            """
        Then its output contains "the host in it: 1"
        And its output contains "no project.yml"
        And its output contains "commits of it: 0"
        And its output contains "in default again: 1"
        And its output contains "asked again: 0"

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
            test -e porelo.yml && echo 'a file was written' || echo 'no file after a no'
            porelo task remove porelo-default-probe --force > /dev/null 2>&1 || true
            rm -rf ~/first-start-resolve
            """
        Then its output contains "no file after a no"
