"""Adds the "T"-Topology

   h1 (h1-eth0, 10.0.0.1/8) --- (s1-eth1) s1 (s1-eth3) --- (h2-eth0, 10.0.0.2/8) h2
                                      (s1-eth2)
                                          |
                                          |
                                      (s2-eth1)
                                          s2

Usage:
sudo mn --custom mininet_T_topo.py --topo T
"""

from mininet.topo import Topo

class TTopo( Topo ):

    def __init__( self ):
        Topo.__init__( self )

        # Add hosts and switches
        h1 = self.addHost( 'h1' )
        h2 = self.addHost( 'h2' )
        s1 = self.addSwitch( 's1' )
        s2 = self.addSwitch( 's2' )

        # Add links
        self.addLink( h1, s1 )
        self.addLink( s1, s2 )
        self.addLink( s1, h2 )


topos = { 'T': ( lambda: TTopo() ) }
