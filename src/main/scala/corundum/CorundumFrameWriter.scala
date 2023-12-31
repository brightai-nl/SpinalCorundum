package corundum

import spinal.core._
import spinal.lib._

import spinal.lib.bus.misc._
import spinal.lib.bus.amba4.axi._

import scala.math._

// companion object
//object CorundumFrameWriter {
//  final val addressWidth = 10
//}

// Write a stream of fragments (i.e. generate a packet stream)

// 0x000   Identifier ("PKWR")
// 0x004   [31:16] Version and [15:0] Revision
// 0x020   Width in bits of CorundumFrame TDATA words
// 0x040   Available space (in number of TDATA words) in the output FIFO
// 0x080   After writing (any value) to this register, the next write to 0x100.. completes the packet
// 0x100.. Write (partial) 32-bit words of the packet word (which can be 512 bits)
//
// The bus data widthe width must be 32-bit currently, and the packet size is a multiple of 32-bits.
// For our purpose, not yet a limitation. Limitation is mostly due to tkeep updates.

case class CorundumFrameWriter(dataWidth : Int) extends Component {
  //require(dataWidth == 64)
  val io = new Bundle {
    // this is where driveFrom() drives into
    val input = slave(Stream(Fragment(CorundumFrame(dataWidth))))
    // and we simply pass it on the output
    val output = master(Stream(Fragment(CorundumFrame(dataWidth))))

    //val slaveAddrWidth = 10
  }
  io.output << io.input

  // Execute the function renameAxiIO after the creation of the component
  addPrePopTask(() => CorundumFrame.renameAxiIO(io))

  def driveFrom(busCtrl : BusSlaveFactory) = new Area {
    // address decoding assumes slave-local addresses
    //assert(busCtrl.busAddressWidth == addressWidth)
    // mostly tkeep is still hardcoded for 32-bit bus controller
    assert(busCtrl.busDataWidth == 32)

    val keepWidth = dataWidth / 8

    // will be used for identification purposes @TODO
    busCtrl.read(B"32'hAABBCCDD", 0x000, documentation = null)
    // 16'b version and 16'b revision
    busCtrl.read(B"32'b00010001", 0x004, documentation = null)
    // some strictly increasing (not per se incrementing) build number
    val gitCommits = B(BigInt(SourceCodeGitCommits()), 32 bits)
    busCtrl.read(gitCommits, 0x008, 0, null)
    // GIT hash
    val gitHash = B(BigInt(SourceCodeGitHash(), 16), 160 bits)
    busCtrl.readMultiWord(gitHash, 0x00c, documentation = null)
    // dataWidth
    val instanceDataWidth = U(dataWidth, 32 bits)
    busCtrl.read(instanceDataWidth, 0x020, 0, null)

    val empty_bytes = Reg(UInt(log2Up(busCtrl.busDataWidth / 8 - 1) bits)) init (0)
    busCtrl.write(empty_bytes, 0x080, documentation = null)

    // 0x100.. write into wide (512-bits?) register
    val stream_word = Reg(Bits(dataWidth bits))
    busCtrl.writeMultiWord(stream_word, 0x100, documentation = null)

    // match a range of addresses using mask
    import spinal.lib.bus.misc.MaskMapping

    // writing packet word content?
    def isAddressed(): Bool = {
      //val mask_mapping = MaskMapping(0xFFFFC0L/*64 addresses, 16 32-bit regs*/, 0x000100L)
      val size_mapping = SizeMapping(0x100, dataWidth / 8)
      val ret = False
      busCtrl.onWritePrimitive(address = size_mapping, false, ""){ ret := True }
      ret
    }

    val addressed = isAddressed()

    // set (per-byte) tkeep bits for all 32-bit registers being written
    val tkeep = Reg(Bits(dataWidth / 8 bits)) init(0)
    // valid will push the current packet word out
    val valid = Reg(Bool) init(False)
    // tlast indicates if the next write on the bus completes the packet
    val tlast = Reg(Bool) init(False)

    val reg_idx = busCtrl.writeAddress.resize(log2Up(dataWidth / 8)) / (busCtrl.busDataWidth / 8)

    valid := False

    when (isAddressed()) {

      val tkeep_full_busword = Bits(busCtrl.busDataWidth / 8 bits) // 4'b for 32-bit data bus
      tkeep_full_busword := (default -> true) // 4'b1111 for 32-bit data bus

      // to minimize timing, limit the range of possible shifts per clock cycle
      // update tkeep on every bus write, compensate for empty_bytes in last bus write
      // empty_bytes is in range [busCtrl.busDataWidth / 8 - 1, 0], so [3, 0] for a 32-bit data bus
      when (reg_idx === 0) {
        tkeep := (tkeep_full_busword |>> empty_bytes).resize(keepWidth) 
      } otherwise {
        tkeep := (tkeep |<< (busCtrl.busDataWidth / 8 - empty_bytes)) | tkeep_full_busword.resize(keepWidth)
      }

      // indicated last write, or writing last word?
      when (tlast || (reg_idx === (dataWidth / busCtrl.busDataWidth - 1))) {
        valid := True
        empty_bytes := 0
      }
    }
    when (valid) {
      tlast := False
    }
    busCtrl.onWrite(0x80, null){
      tlast := True
    }

    val corundum = Stream Fragment(CorundumFrame(dataWidth))
    corundum.last := tlast
    corundum.valid := valid
    corundum.payload.tdata := stream_word
    corundum.payload.tkeep := tkeep
    corundum.payload.tuser := 0

    val fifoDepth = 4
    val (fifo, fifoAvailability) = corundum.queueWithAvailability(fifoDepth) //.init(0)
    busCtrl.read(fifoAvailability, address = 0x40)
    io.input << fifo
  }
}

// companion object
object CorundumFrameWriterAxi4 {
  final val slaveAddressWidth = 10
  // generate VHDL and Verilog
  def main(args: Array[String]) {
    val vhdlReport = Config.spinal.generateVhdl(new CorundumFrameWriterAxi4(Config.corundumDataWidth, Axi4Config(32, 32, 2, useQos = false, useRegion = false)))
    val verilogReport = Config.spinal.generateVerilog(new CorundumFrameWriterAxi4(Config.corundumDataWidth, Axi4Config(32, 32, 2, useQos = false, useRegion = false)))
  }
}

// slave must be naturally aligned
case class CorundumFrameWriterAxi4(dataWidth : Int, busCfg : Axi4Config) extends Component {
  // copy AXI4 properties from bus, but override address width from slave
  val slaveCfg = busCfg.copy(addressWidth = CorundumFrameWriterAxi4.slaveAddressWidth)
  
  val io = new Bundle {
    val output = master(Stream(Fragment(CorundumFrame(dataWidth))))
    val ctrlbus = slave(Axi4(slaveCfg))
  }

  val writer = CorundumFrameWriter(dataWidth)
  val ctrl = new Axi4SlaveFactory(io.ctrlbus)
  val bridge = writer.driveFrom(ctrl)
  io.output << writer.io.output

  // Execute the function renameAxiIO after the creation of the component
  addPrePopTask(() => CorundumFrame.renameAxiIO(io))
}
