from __future__ import annotations

from foliarc_agent.agent.multi_agent import EditorialBlackboard, EditorialTeamOrchestrator
from foliarc_agent.streaming.llm_client import build_novel_messages


def test_two_layer_anti_spoiler_isolation_and_critic_guard_brief() -> None:
    latent_secret = "【机密底牌】掌门其实是十年前血洗青云宗的真正幕后黑手"
    payoff_quote = "第四章原文：青铜玉佩背面刻着半朵血莲，遇月光渗出寒雾。"

    board = EditorialBlackboard(
        run_id="run-58b-1",
        owner_id="author-1",
        book_id="book-1",
        chapter_no=180,
        book_title="星渊迷局",
        genre="悬疑仙侠",
        chapter_title="血莲重现",
        pov_character="林青",
        foreshadowing_writer_pack=(
            f"[PAYOFF #F01 残缺青铜玉佩] 解锁真相：玉佩是开启星渊地宫的钥匙。\n"
            f"当年原文锚点：{payoff_quote}\n"
            "[潜伏伏笔 #F02 掌门古怪玉戒 | 严禁提前点破真相] 表面线索：掌门左手总戴着一枚冰凉黑戒。"
        ),
        foreshadowing_critic_pack=(
            f"[PAYOFF #F01 残缺青铜玉佩] 须核验与当年原文一致：{payoff_quote}\n"
            f"[防提前剧透审查 #F02 掌门古怪玉戒] {latent_secret}（严禁在正文提前暴露！）"
        ),
        critic_guard_brief="已亡角色红线：二长老(Ch.48)、林默(Ch.122)；当前视角约束：林青",
        outline_text="1. 林青于地宫石门前取出青铜玉佩。\n2. 血莲纹路发光解开封印。",
        draft_text="林青取出青铜玉佩，背面半朵血莲在月光下泛出寒雾，石门轰然洞开。",
    )

    director_msgs = EditorialTeamOrchestrator._build_director_messages(board)
    writer_msgs = EditorialTeamOrchestrator._build_writer_messages(board, skill_body="")
    critic_msgs = EditorialTeamOrchestrator._build_critic_messages(board)

    # 1. Writer & Director MUST receive PAYOFF original quote and POV constraint, NEVER latent HIDE_TRUTH secret
    assert payoff_quote in director_msgs[1]["content"]
    assert payoff_quote in writer_msgs[1]["content"]
    assert "林青" in writer_msgs[1]["content"]
    assert latent_secret not in director_msgs[1]["content"]
    assert latent_secret not in writer_msgs[1]["content"]
    assert "严禁提前点破真相" in writer_msgs[0]["content"]

    # 2. Critic MUST receive latent secret (for spoiler leak detection) + dead-character tombstones + POV guard
    assert latent_secret in critic_msgs[1]["content"]
    assert "二长老(Ch.48)" in critic_msgs[1]["content"]
    assert "已亡角色红线" in critic_msgs[1]["content"]
    assert "读心术" in critic_msgs[0]["content"]

    # 3. Direct mode also injects POV & writer foreshadowing pack cleanly
    direct_msgs = build_novel_messages(
        action="CONTINUE",
        book_title="星渊迷局",
        genre="悬疑仙侠",
        synopsis="",
        chapter_title="血莲重现",
        chapter_context="",
        instruction="续写地宫开启",
        pov_character="林青",
        foreshadowing_writer_pack=board.foreshadowing_writer_pack,
    )
    assert payoff_quote in direct_msgs[1]["content"]
    assert "林青" in direct_msgs[1]["content"]
    assert latent_secret not in direct_msgs[1]["content"]
