#!/bin/dash
echo $PWD
SESSION=flowvisor

tmux -2 new-session -d -s $SESSION

# tmux new-window -t $SESSION:1 -n 'mininet'
tmux send-keys "sudo mn --custom mininet_T_topo.py --topo T --controller=remote,port=6633" C-m
tmux split-window -h
tmux select-pane -t 1
tmux send-keys "ryu run --ofp-tcp-listen-port 6634 ryu_app.py &> ryu1.log &" C-m "less +F ryu1.log"
tmux split-window -v -p 66
tmux send-keys "ryu run --ofp-tcp-listen-port 6635 ryu_app.py &> ryu2.log &" C-m "less +F ryu2.log"
tmux split-window -v
tmux send-keys "../../gradlew :examples:flowvisor:installDist" C-m "./flowvisor >flowvisor.log & less +F flowvisor.log"
tmux -2 attach-session -t $SESSION
