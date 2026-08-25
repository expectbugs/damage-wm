package wm.damage.core.transport

/** The two BLE links of the pair — one per temple. Every layer names them the
 *  same way: the transport writes to an arm, the firmware model keeps a lens
 *  context per arm, the replicas draw one arm's panel. */
enum class Arm { LEFT, RIGHT }
