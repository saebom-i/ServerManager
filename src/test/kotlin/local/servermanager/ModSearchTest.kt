package local.servermanager

import org.junit.jupiter.api.Test
import kotlin.test.*

class ModSearchTest {
    private fun mod(id:String,vararg deps:String)=InstalledMod("$id.jar",true,10,id,id,ModArchiveInfo("fabric",listOf(ModIdentity(id,"1",id)),deps.map {ModRequirement(id,it,listOf("*"),"required")}))
    private fun graph(vararg mods:InstalledMod)=ModGraph(ModInventorySnapshot("r",mods.toList(),emptyList()))
    @Test fun `dependencies aliases transitive protection and reverse dependent removal`() {
        val lib=mod("lib").let {it.copy(metadata=it.metadata!!.copy(mods=listOf(ModIdentity("lib","1","Library",setOf("alias")))))}
        val g=graph(mod("main","alias"),lib,mod("extra","main"),mod("other"))
        assertEquals(setOf("main.jar","lib.jar","extra.jar"),g.disable(setOf("lib.jar")))
        val p=g.plan(setOf("lib.jar","main.jar","other.jar"),setOf("main.jar"))
        assertEquals(listOf("lib.jar","main.jar"),p.protectedSuspects)
        assertEquals(listOf("other.jar"),p.groups.single().files)
        assertEquals(setOf("other.jar"),g.disabled(p,emptySet()))
    }
    @Test fun `candidate dependencies through nonsuspects and cycles form inseparable groups`() {
        val g=graph(mod("a","bridge"),mod("bridge","b"),mod("b","a"),mod("c"),mod("addon","c"))
        val p=g.plan(setOf("a.jar","b.jar","c.jar"),emptySet())
        assertEquals(2,p.groups.size)
        assertEquals(setOf("a.jar","b.jar"),p.groups.first {"a.jar" in it.files}.files.toSet())
        val c=p.groups.first {"c.jar" in it.files}
        assertEquals(setOf("c.jar","addon.jar"),g.disabled(p,p.groups.map {it.id}.toSet()-c.id))
    }
    @Test fun `missing and unknown dependencies block automatic modification`() {
        val missing=graph(mod("a","missing"));assertFails {missing.plan(setOf("a.jar"),emptySet())}
        assertEquals(setOf("a.jar"),missing.disable(setOf("a.jar")))
        assertFails {graph(mod("a").copy(metadata=null))}
        val g=graph(mod("a","minecraft","fabricloader","java"))
        assertEquals(setOf("a.jar"),g.closure(setOf("a.jar")))
    }
    private fun solve(all:List<String>,fails:(List<String>)->Boolean):Pair<SearchStep,List<SearchObservation>> {
        val history=mutableListOf<SearchObservation>()
        repeat(200){val s=ManualModSearch.next(all,history);if(s.status!="TEST")return s to history;history.add(SearchObservation(s.groups,if(fails(s.groups))"FAIL" else "PASS"))}
        error("search did not finish")
    }
    @Test fun `single culprit uses logarithmic trials and requires human baseline comparisons`() {
        val all=(1..128).map {"m$it"}
        val (result,history)=solve(all){"m93" in it}
        assertEquals("FOUND",result.status);assertEquals(listOf("m93"),result.groups)
        assertTrue(history.size<=18);assertEquals(all.sorted(),history.first().groups);assertTrue(history[1].groups.isEmpty())
        assertEquals("OUTSIDE_SELECTION",solve(all){true}.first.status)
        assertEquals("NOT_REPRODUCED",solve(all){false}.first.status)
    }
    @Test fun `interacting mods and multiple independent causes do not claim every culprit is found`() {
        val all=listOf("a","b","c","d","e")
        assertEquals(listOf("b","e"),solve(all){it.containsAll(listOf("b","e"))}.first.groups)
        val first=solve(all){"b" in it || "e" in it}.first
        assertEquals(1,first.groups.size)
        val second=solve(all-first.groups.toSet()){"b" in it || "e" in it}.first
        assertEquals(setOf("b","e"),(first.groups+second.groups).toSet())
    }
    @Test fun `inconclusive blocks advancement and correction replays decisions`() {
        val all=listOf("a","b","c","d")
        val history=mutableListOf(SearchObservation(all,"FAIL"),SearchObservation(emptyList(),"PASS"))
        val next=ManualModSearch.next(all,history)
        history.add(SearchObservation(next.groups,"INVALID"))
        assertEquals("INCONCLUSIVE",ManualModSearch.next(all,history).status)
        assertEquals(next.groups,ManualModSearch.next(all,history).groups)
        history.add(SearchObservation(next.groups,"FAIL"))
        assertTrue(ManualModSearch.next(all,history).groups.size<next.groups.size)
        history.add(SearchObservation(all,"PASS"))
        assertEquals("NOT_REPRODUCED",ManualModSearch.next(all,history).status)
    }
}
