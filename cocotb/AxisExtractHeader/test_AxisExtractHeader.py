#!/usr/bin/env python
"""

Copyright 2020, The Regents of the University of California.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

   1. Redistributions of source code must retain the above copyright notice,
      this list of conditions and the following disclaimer.

   2. Redistributions in binary form must reproduce the above copyright notice,
      this list of conditions and the following disclaimer in the documentation
      and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE REGENTS OF THE UNIVERSITY OF CALIFORNIA ''AS
IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE REGENTS OF THE UNIVERSITY OF CALIFORNIA OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
OF SUCH DAMAGE.

The views and conclusions contained in the software and documentation are those
of the authors and should not be interpreted as representing official policies,
either expressed or implied, of The Regents of the University of California.

"""

import itertools
import logging
import os
import binascii
import cocotb_test.simulator

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge
from cocotb.regression import TestFactory

from cocotbext.axi import AxiStreamBus, AxiStreamFrame, AxiStreamSource, AxiStreamSink


class TB(object):
    def __init__(self, dut):
        self.dut = dut

        self.log = logging.getLogger("cocotb.tb")
        self.log.setLevel(logging.DEBUG)

        cocotb.fork(Clock(dut.clk, 4, units="ns").start())

        # connect TB source to DUT sink, and vice versa
        # byte_lanes = 16 is workaround for https://github.com/alexforencich/cocotbext-axi/issues/46
        self.source = AxiStreamSource(AxiStreamBus.from_prefix(dut, "sink"),     dut.clk, dut.reset, byte_lanes = 16)
        self.sink =   AxiStreamSink  (AxiStreamBus.from_prefix(dut, "source"  ), dut.clk, dut.reset, byte_lanes = 16)

    def set_idle_generator(self, generator=None):
        if generator:
            self.source.set_pause_generator(generator())

    async def reset(self):
        self.dut.reset.setimmediatevalue(0)
        await RisingEdge(self.dut.clk)
        await RisingEdge(self.dut.clk)
        self.dut.reset.value = 1
        await RisingEdge(self.dut.clk)
        await RisingEdge(self.dut.clk)
        self.dut.reset.value = 0
        await RisingEdge(self.dut.clk)
        await RisingEdge(self.dut.clk)


async def run_test(dut, payload_lengths=None, payload_data=None, header_lengths=None, idle_inserter=None):

    tb = TB(dut)

    await tb.reset()

    tb.set_idle_generator(idle_inserter)

    test_pkts = []
    test_frames = []


#    for payload in [payload_data(x) for x in payload_lengths()]:
#        #payload[6] = b'\00'
#        test_pkt = bytearray(payload)

    for pkt_len in payload_lengths():
        payload = payload_data(pkt_len)
        #payload[6] = b'\00'
        test_pkt = bytearray(payload)

        tb.log.info(type('payload'))
        tb.log.info("Sending packet: %s" % bytes(test_pkt))

        test_pkts.append(test_pkt)
        test_frame = AxiStreamFrame(test_pkt)
        test_frames.append(test_frame)

        tb.dut.sink_length = pkt_len

        await tb.source.send(test_frame)

    for test_pkt, test_frame in zip(test_pkts, test_frames):
        tb.log.info("Waiting to receive packet on our sink.")
        rx_frame = await tb.sink.recv()
        rx_pkt = bytes(rx_frame)
        tb.log.info("RX packet: %s", repr(rx_pkt))
        # padded to 60 if the packet size is less than 60
        padded_pkt = test_pkt.ljust(60, b'\x00')
        #assert rx_frame.tdata == padded_pkt


        await RisingEdge(dut.clk)
        await RisingEdge(dut.clk)
        await RisingEdge(dut.clk)
        await RisingEdge(dut.clk)
        await RisingEdge(dut.clk)
        await RisingEdge(dut.clk)
        await RisingEdge(dut.clk)
        await RisingEdge(dut.clk)
        await RisingEdge(dut.clk)


    assert tb.sink.empty()

    await RisingEdge(dut.clk)
    await RisingEdge(dut.clk)

def cycle_pause():
    return itertools.cycle([1, 1, 1, 0])


def size_list():
    return list(range(1, 129))

def payload_size_list():
    #return list(range(14, 10))
    return list(range(20, 21))

def header_size_list():
    return list(range(1, 4))


def incrementing_payload(length):
    #return bytes(itertools.islice(itertools.cycle(range(1, 256)), length))
    return bytearray(itertools.islice(itertools.cycle(range(1, 256)), length))


if cocotb.SIM_NAME:

    factory = TestFactory(run_test)
    factory.add_option("payload_lengths", [payload_size_list])
    factory.add_option("payload_data", [incrementing_payload])
    factory.add_option("idle_inserter", [None, cycle_pause])
    factory.generate_tests()

    #factory = TestFactory(run_test_pad)
    #factory.add_option("payload_data", [incrementing_payload])
    #factory.add_option("idle_inserter", [None, cycle_pause])
    #factory.generate_tests()


# cocotb-test

tests_dir = os.path.dirname(__file__)
#rtl_dir = os.path.abspath(os.path.join(tests_dir, '..', '..', 'rtl'))
rtl_dir = os.path.abspath(os.path.join(tests_dir, '..', '..'))
#lib_dir = os.path.abspath(os.path.join(rtl_dir, '..', 'lib'))
#axi_rtl_dir = os.path.abspath(os.path.join(lib_dir, 'axi', 'rtl'))
#axis_rtl_dir = os.path.abspath(os.path.join(lib_dir, 'axis', 'rtl'))
#eth_rtl_dir = os.path.abspath(os.path.join(lib_dir, 'eth', 'rtl'))
#pcie_rtl_dir = os.path.abspath(os.path.join(lib_dir, 'pcie', 'rtl'))


def test_AxisExtractHeader(request):
    dut = "AxisExtractHeader"
    module = os.path.splitext(os.path.basename(__file__))[0]
    toplevel = dut

    verilog_sources = [
        os.path.join(rtl_dir, f"{dut}.v"),
    ]

    parameters = {}

    parameters['DATA_WIDTH'] = 16 * 8 #512
    # divide by 8?
    parameters['KEEP_WIDTH'] = parameters['DATA_WIDTH'] / 8

    extra_env = {f'PARAM_{k}': str(v) for k, v in parameters.items()}

    sim_build = os.path.join(tests_dir, "sim_build",
        request.node.name.replace('[', '-').replace(']', ''))

    cocotb_test.simulator.run(
        python_search=[tests_dir],
        verilog_sources=verilog_sources,
        toplevel=toplevel,
        module=module,
        parameters=parameters,
        sim_build=sim_build,
        extra_env=extra_env,
    )
