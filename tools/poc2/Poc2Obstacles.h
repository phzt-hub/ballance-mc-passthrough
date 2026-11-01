#ifndef POC2_OBSTACLES_H
#define POC2_OBSTACLES_H

// PoC-2 scaffold (ballance-mc-passthrough).
// Injected into Source/BuildingBlocks/physics_RT by apply-physics-hook.py.
// Everything is inert unless BALLANCE_POC2_COUNT (or BALLANCE_POC2_LOG) is set.

class CKIpionManager;

double Poc2NowMs();
void Poc2BeforeFrame(CKIpionManager *man);
void Poc2AfterFrame(CKIpionManager *man, double simulateMs);

#endif
