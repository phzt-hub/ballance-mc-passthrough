# 2026-11-04 17:3x 更新：线一 levelboot 玩法接管——找到激活引擎：CKScene::Init / ActivateAllScript

用户上一轮选 1 = 线一 levelboot 玩法接管。本轮只做源码排查，**未改 Ballanced 源码、未提交 git、未重建**。

## 一句话
「关卡几何并入但玩法不启动」的根因不是缺脚本，而是**脚本的 Start 输入从未被激活**。
CK2 的激活引擎是 CKScene::Init()：按对象的 CK_SCENEOBJECT_START_ACTIVATE 标志调 beh->Activate(active, reset)。
base.cmo 载入后 CKContext::Play() 只在 m_Reseted 为真时才调 ActivateAllScript()，初始载入 m_Reseted=FALSE，
所以只靠 scene 默认标志激活；Loading_Manager / Level_Init / Gameplay_Init / Balls_Init 的 Start 全是 inactive，正是缺的那一环。

## 本轮源码实证（均在 ~/src/Ballanced/Source/CK2）
| 位置 | 事实 |
|---|---|
| CKScene::Init (CKScene.cpp ~537) | 遍历 scene 对象；active = (activityFlags==ACTIVATE) 或 desc->m_Flags & CK_SCENEOBJECT_START_ACTIVATE；active 则置 CK_SCENEOBJECT_ACTIVE、beh->Activate(active,reset)、behaviorManager->AddObject(beo) |
| CKLevel::LaunchScene (CKLevel.cpp:174) | newScene->Init(m_RenderContextList, Active, Reset)；默认 Active=CK_SCENEOBJECTACTIVITY_SCENEDEFAULT、Reset=CK_SCENEOBJECTRESET_RESET |
| CKLevel::ActivateAllScript (CKLevel.cpp:603) | 遍历 level scene 全部对象 + m_SceneList + m_ScriptArray，对 GetType()==CKBEHAVIORTYPE_SCRIPT 的脚本调 script->Activate(TRUE,TRUE) |
| CKContext::Play (CKContext.cpp:246) | if (m_Reseted) { ...; currentLevel->ActivateAllScript(); } → 初始载入不调 ActivateAllScript |
| CKBehavior::Reset (CKBehavior.cpp:2194) | 清空全部 in/out IO，然后 if (CKBEHAVIOR_SCRIPT) ActivateInput(0, TRUE) → Reset 会把脚本的 Start(in[0]) 置 active |
| CKBehavior::Activate(Active,breset) (CKBehavior.cpp:1176) | if (breset || flags==NONE) Reset(); if(Active) flags |= CKBEHAVIOR_ACTIVE |
| CKBehavior::GetType (CKBehavior.cpp:73) | CKBEHAVIOR_SCRIPT(0x2) → CKBEHAVIORTYPE_SCRIPT |
| CKBehavior::Execute (CKBehavior.cpp:344) | 无字节码 VM：USEFUNCTION→ExecuteFunction()，否则逐子行为 subBehavior->Execute(delta) + FindNextBehaviorsToExecute |
| CKBeObject::ExecuteBehaviors (CKBeObject.cpp:16) | 遍历 m_ScriptArray，beh->IsActive() 才 Execute(delta) |
| CKBehavior::CheckIOsActivation (CKBehavior.cpp:2486) | 按 m_SubBehaviorLinks 的 inIO->IsActive() 激活子行为 outIO，末尾重建 m_BehaviorIterators |
| CKBehavior::FindNextBehaviorsToExecute (CKBehavior.cpp:2674) | 子行为 outIO active → 激活 dstIO，dstBeh->ModifyFlags(CKBEHAVIOR_ACTIVE,0) |
| CKLevel::AddObject (CKLevel.cpp:15) | levelScene->AddObjectToScene(obj, TRUE) → 合并对象确实进 level scene |
| CGamePlayer::Load (GamePlayer.cpp:295) | m_CKContext->Reset(); ClearAll(); 再载文件 —— stock Load 会清空 base |

## 下一步做什么
在 LoadLevel() 合并 + level->LaunchScene(NULL) 之后加一步激活：
- 方案 A（最忠实）：level->ActivateAllScript() —— 会同时激活 Loading_Manager，可能重复加载 NMO，有风险但最接近原版。
- 方案 B（保守）：按名字只激活关卡初始化链：Levelinit_Init / Levelinit_build / Level_Init / Gameplay_Init / Balls_Init / AnimTrafo_Init / MenuLevel_Init / Default Level（跳过 Loading_Manager / Delete_* / Load*）。
- 新开关 BALLANCE_POC_START_LEVEL=1（all）或 =chain（默认 chain）。
- 激活 API：CKBehavior::Activate(TRUE,TRUE)（等价 Reset + 置 ACTIVE，Start 自动 active），或 CKScene::ActivateScript(beh,TRUE,TRUE)。
- 预期观测：相机从 Cam_MenuLevel 切到玩法相机；球出现；3d 对象数/物理变化。

## 落盘文件（本轮未新增）
- 无新增。已有 tools/levelboot/{apply-levelboot-hook.py, levelboot.cpp, pocframelog.cpp, pocscripts.cpp, nmo_inv.cpp}。
- 上轮回放日志 /tmp/nmo-run2.txt（4 NMO 并入 403 对象，context 6132->21290，相机仍 Cam_MenuLevel）。

## 其它
- Ballanced 三个子模块仍是脏工作区（补丁状态），git submodule update 会冲掉。
- 未动 128、未动新作17、未动 dsh-backup.sh。
- ask_user_question 在本环境挂死，不要再用；需要决策直接在回复里给选项。
