# https://docs.vagrantup.com
Vagrant.configure("2") do |config|
  config.vm.box = "ubuntu/xenial64"
  config.ssh.forward_x11 = true  # for spawning terminals for hosts/switches within mininet
  config.vm.provision "shell", inline: <<-EOF
		apt-get update
		apt-get install -y openjdk-8-jdk

		### ryu
		apt-get install -y python3-pip
		pip3 install ryu==4.13

		### floodlight
		apt-get install ant
		mkdir floodlight
		curl -L https://github.com/floodlight/floodlight/archive/v1.2.tar.gz | tar -xz -C floodlight --strip-components=1
		cd floodlight
		ant
		cd ..

		### mininet and openvswitch
		curl -L https://github.com/mininet/mininet/archive/2.2.2.tar.gz | tar -xz
		# install.sh expects the directory to be named "mininet"
		mv mininet-2.2.2 mininet
		# -n: MiniNet dependencies + core files
		# -w: Wireshark with OpenFlow dissector
		# -V: given version of Open vSwitch
		mininet/util/install.sh -n -w -V 2.7.0

		### cbench
        mkdir oflops
		curl -L https://git.scc.kit.edu/sherter/oflops/repository/7d1c26ac3e292f3d80c02c3ad20f73daf81fe103/archive.tar.gz | tar -xz  -C oflops --strip-components=1
		mkdir openflow
		curl -L https://github.com/mininet/openflow/archive/9f587fc8e657a248d46b4763cc7e72efaccf8e00.tar.gz | tar -xz -C openflow --strip-components=1
		apt-get install -y autoconf libtool libsnmp-dev libpcap-dev libconfig-dev
		cd oflops
		./boot.sh
		./configure
		make && make install
		cd ..

		### ofcprobe
curl -L https://github.com/lsinfo3/ofcprobe/releases/download/v1.0.2/ofcprobe-1.0.2.one-jar.jar -o ofcprobe.jar

		### D-ITG
		apt-get install -y unzip
		curl -L http://traffic.comics.unina.it/software/ITG/codice/D-ITG-2.8.1-r1023-src.zip > ditg.zip
		unzip ditg.zip
		rm ditg.zip
		mv D-ITG-2.8.1-r1023 ditg
		make -C ditg/src
		make -C ditg/src install

		### Various experiments stuff
		pip3 install dpkt		
	EOF
end
