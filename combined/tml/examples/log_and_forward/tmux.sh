#!/bin/dash

SESSION=log_and_forward

tmux -2 new-session -d -s $SESSION

tmux send-keys "ryu run --ofp-tcp-listen-port 6634 simple_switch.py &> simple_switch.log &" C-m "less -S +F simple_switch.log"
tmux split-window -v -p 66
tmux send-keys "../log_and_forward/build/install/log_and_forward/bin/log_and_forward --listen 0.0.0.0:6633 --upstream 127.0.0.1:6634 > log_and_forward.log &" C-m "less -S +F log_and_forward.log"
tmux split-window -v
tmux send-keys "sudo mn --controller=remote,port=6633"
tmux -2 attach-session -t $SESSION
