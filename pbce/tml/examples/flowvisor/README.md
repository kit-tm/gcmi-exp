# Usage
Run `../../gradlew installDist` to build and install this example
(into directory `./build/install/flowvisor`).

A proper environment can be created by running `./flowvisor.tmux`.
This will create a `tmux` session (`man 1 tmux`) with multiple panes,
set up in a way to get going in no time.

The example network is divided into two slices. Packet_In messages
carrying ARP packets are sent (only) to the controller of slice 1
(`ryu1.log`) and Packet_In messages carrying IPv4 packets are sent to
the controller of slice 2 (`ryu2.log`).
