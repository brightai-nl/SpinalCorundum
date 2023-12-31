package corundum

import spinal.core._
import spinal.lib._

import spinal.lib.bus.misc._
import spinal.lib.bus.amba4.axi._

import scala.math._

// companion object
object AxisExtractHeader {
  def main(args: Array[String]) : Unit = {
    val vhdlReport = Config.spinal.generateVhdl(new AxisExtractHeader(Config.cryptoDataWidth, 14/*Ethernet header size in bytes*/))
    val verilogReport = Config.spinal.generateVerilog(new AxisExtractHeader(Config.cryptoDataWidth, 14/*Ethernet header size in bytes*/))
  }
}

/* Split off a fixed size header (for example 14 bytes Ethernet header in the first bytes), pass the remaining payload
 *
 * sink accepts AXIS frames (Ethernet packet)
 * sink_length is the input packet length in bytes, this packet arrives on the sink
 *
 * source is the output packet (Ethernet payload)
 * source_length is the output packet (Ethernet payload)
 */

case class AxisExtractHeader(dataWidth : Int, headerWidthBytes: Int) extends Component {
  val headerWidth = headerWidthBytes * 8
  val dataWidthBytes = dataWidth / 8
  // currently only single beat headers are supported to be stripped off
  require(headerWidth <= dataWidth, s"headerWidth <= dataWidth, needed because AxisExtractHeader does not support multibeat headers yet.")
  val io = new Bundle {
    // I/O is only the Corundum Frame tdata payload
    val sink = slave Stream(Fragment(Bits(dataWidth bits)))
    val source = master Stream(Fragment(Bits(dataWidth bits)))
    // sink_length is given in bytes
    val sink_length = in UInt(12 bits)

    val header = out Bits(headerWidth bits)
    val source_length = out UInt(12 bits)
    val source_remaining = out UInt(12 bits)
  }

  // translateWith() for Stream(Fragment())
  // (before this we needed to work-around this, see AxisUpSizer.scala commented out code)
  implicit class FragmentPimper[T <: Data](v: Fragment[T]) {
    def ~~[T2 <: Data](trans: T => T2) = {
      val that = trans(v.fragment)
      val res = Fragment(cloneOf(that))
      res.fragment := trans(v.fragment)
      res.last := v.last
      res
    }
  }

  // x1 is sink, but adds the sink_length as stream payload
  // such that both sink and sink_length are skid buffered
  val x1 = Stream(Fragment(Bits(dataWidth + 12 bits)))
  x1 << io.sink.~~(_.~~(io.sink_length.asBits ## _)).s2mPipe().m2sPipe()
   
  // y is input stream with original payload, but after the skid buffer
  val x = Stream(Fragment(Bits(dataWidth bits)))
  x << x1.~~(_.~~(_.resize(dataWidth)))
  val x_length = (x1.payload.fragment >> dataWidth).asUInt

  // @TODO thorough design review needed

  // extract header at first beat
  val x_is_frame_continuation = RegNextWhen(!x.last, x.fire).init(False)
  val x_is_first_beat = x.fire & !x_is_frame_continuation
  val x_header = RegNextWhen(x.payload.resize(headerWidth), x_is_first_beat)
  val x_is_single_beat = x_is_first_beat && x.last

  val remaining = Reg(SInt(13 bits))
  val source_payload_length = Reg(SInt(13 bits))
  val calculated_payload_length = Reg(SInt(13 bits))

// for production use this
//  } otherwise /* { not first beat } */ {
//    remaining := remaining - dataWidth / 8

  val z = Stream(Fragment(Bits(dataWidth bits)))

  val y_valid = Reg(Bool).init(False)
  val y_last = RegNextWhen(x.last, x.fire)
  // y holds previous valid x.payload, but only if x is non-last
  val y = RegNextWhen(x.payload |>> headerWidth, x.fire)
  val y_is_single_beat = RegNextWhen(x_is_single_beat, x.fire)

  val bytes_in_last_input_beat = Reg(UInt(log2Up(dataWidthBytes) + 1 bits))
  when (x_is_first_beat) {
    remaining := x_length.asSInt.resize(13 bits) - headerWidthBytes
    source_payload_length := x_length.asSInt.resize(13 bits) - headerWidthBytes
    // calculate number of bytes in last input beat of packet
    bytes_in_last_input_beat := x_length % dataWidthBytes
    when (bytes_in_last_input_beat === 0) {
      bytes_in_last_input_beat := dataWidthBytes
    }
  // elsewhen case can be removed, only for clearity during development cycle
  } elsewhen (z.last & z.fire) {
    remaining := 0
  } elsewhen (z.fire) {
    remaining := remaining - dataWidth / 8
  } otherwise {
    remaining := remaining
  }

  // if output packet is one beat smaller than input packet
  val is_one_beat_less = (bytes_in_last_input_beat <= headerWidthBytes)

  // determine when y becomes valid or invalid
  val remaining_y = RegNextWhen(remaining - dataWidth/8, x.fire, S(0))

  // x is valid last word, last word for z comes from x and y combined
  when (z.fire & z.payload.last) {
    y_valid := x.valid
  } elsewhen (x.fire & !z.fire) {
    y_valid := x.valid
  // z takes x, no new x
  } elsewhen (z.fire & !x.fire) {
    y_valid := False
  }

  val y_has_last_data = y_valid & x.valid  & ((remaining >= 1) & (remaining <= dataWidth/8))
  val x_has_last_data = y_valid & x.valid & x.last & (remaining >= headerWidthBytes) & (remaining <= dataWidth/8)

  z.payload.last := y_has_last_data | x_has_last_data
  //z.valid := (y_valid & z.payload.last) | (x.valid & y_valid)
  z.valid := (y_valid & z.payload.last) | (x.valid & y_valid & (remaining >= dataWidth/8))
  //z.valid := (y_valid & y_is_single_beat) | (x.valid & y_valid)
  z.payload.fragment := Mux(z.valid, x.payload.fragment(headerWidth - 1 downto  0) ## y(dataWidth - headerWidth - 1 downto 0), B(0))
  // z holds valid word when y is a single beat, or when we can combine x with a non-last y
  x.ready := z.ready


  io.source <-< z
  io.source_length := RegNextWhen(Mux(source_payload_length < 0, U(0), source_payload_length.asUInt.resize(12)), z.ready)
  io.source_remaining := RegNextWhen(Mux(remaining < 0, U(0), remaining.asUInt.resize(12)), z.ready)
  io.header := RegNextWhen(x_header, z.ready)

   // Execute the function renameAxiIO after the creation of the component
  addPrePopTask(() => CorundumFrame.renameAxiIO(io))
}

//Generate the AxisExtractHeader's Verilog
object AxisExtractHeaderVerilog {
  def main(args: Array[String]) {
//    val toplevel = new AxisExtractHeader(128, 14/*Ethernet header size in bytes*/)
//    val config = SpinalConfig()
//    config.generateVerilog(toplevel)
//    SpinalVerilog(toplevel)
    SpinalVerilog(new AxisExtractHeader(128, 14/*Ethernet header size in bytes*/))
  }
}

case class AxisCheck() extends Component {
  val io = new Bundle {
    // I/O is only the Corundum Frame tdata payload
    val sink = slave Stream(Fragment(Bits(8 bits)))
    val source = master Stream(Fragment(Bits(8 bits)))
  }
  io.source << io.sink.m2sPipe()
}

object AxisCheckVerilog {
  def main(args: Array[String]) {
    SpinalVerilog(new AxisCheck())
  }
}
