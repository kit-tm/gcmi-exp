"""Adds the "T"-Topology

   host --- switch --- host
              |
              |
            switch

Usage:
sudo mn --custom mininet_T_topo.py --topo T --controller=remote,ip=192.168.122.1,port=50000
"""

from mininet.topo import Topo

class TTopo( Topo ):

    def __init__( self ):
        Topo.__init__( self )

        # Add hosts and switches
        leftHost = self.addHost( 'h1' )
        rightHost = self.addHost( 'h2' )
        delegationSwitch = self.addSwitch( 's1' )
        evictionSwitch = self.addSwitch( 's2' )

        # Add links
        self.addLink( leftHost, delegationSwitch )
        self.addLink( delegationSwitch, evictionSwitch )
        self.addLink( delegationSwitch, rightHost )


topos = { 'T': ( lambda: TTopo() ) }
