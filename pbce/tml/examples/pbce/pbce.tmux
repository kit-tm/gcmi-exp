#!/bin/dash
echo $PWD
SESSION=pbce

tmux -2 new-session -d -s $SESSION

# tmux new-window -t $SESSION:1 -n 'mininet'
tmux send-keys "sudo mn --custom mininet_T_topo.py --topo T --controller=remote,port=6633" C-m
tmux split-window -h
tmux send-keys "../log_and_forward/build/install/log_and_forward/bin/log_and_forward --listen 0.0.0.0:6633 --upstream 127.0.0.1:6634" C-m
tmux split-window -v -p 66
tmux send-keys "build/install/pbce/bin/pbce -l 0.0.0.0:6634 -u 127.0.0.1:6635 -m 0.0.0.0:50000" C-m
tmux split-window -v
tmux send-keys "../log_and_forward/build/install/log_and_forward/bin/log_and_forward --listen 0.0.0.0:6635 --upstream 127.0.0.1:6636" C-m
tmux select-pane -t 0
tmux split-window -v -p 66
tmux send-keys "ryu run --ofp-tcp-listen-port 6636 simple_switch.py" C-m
tmux split-window -v
tmux send-keys "telnet localhost 50000"
tmux -2 attach-session -t $SESSION
