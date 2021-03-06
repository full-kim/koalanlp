@file:JvmName("Util")
@file:JvmMultifileClass

package kr.bydelta.koala.hnn

import edu.berkeley.nlp.PCFGLA.*
import edu.berkeley.nlp.syntax.Trees
import edu.berkeley.nlp.util.Numberer
import kaist.cilab.jhannanum.common.Eojeol
import kaist.cilab.jhannanum.common.communication.PlainSentence
import kaist.cilab.jhannanum.common.communication.Sentence
import kaist.cilab.jhannanum.common.communication.SetOfSentences
import kaist.cilab.jhannanum.common.workflow.Workflow
import kaist.cilab.jhannanum.morphanalyzer.chartmorphanalyzer.MorphemeChart
import kaist.cilab.jhannanum.morphanalyzer.chartmorphanalyzer.PostProcessor
import kaist.cilab.jhannanum.morphanalyzer.chartmorphanalyzer.Simti
import kaist.cilab.jhannanum.plugin.major.morphanalyzer.MorphAnalyzer
import kaist.cilab.jhannanum.plugin.major.morphanalyzer.impl.ChartMorphAnalyzer
import kaist.cilab.jhannanum.plugin.major.postagger.impl.HMMTagger
import kaist.cilab.jhannanum.plugin.supplement.MorphemeProcessor.UnknownMorphProcessor.UnknownProcessor
import kaist.cilab.jhannanum.plugin.supplement.PlainTextProcessor.InformalSentenceFilter.InformalSentenceFilter
import kaist.cilab.jhannanum.plugin.supplement.PlainTextProcessor.SentenceSegmentor.SentenceSegmentor
import kaist.cilab.parser.berkeleyadaptation.Configuration
import kaist.cilab.parser.corpusconverter.sejong2treebank.sejongtree.NonterminalNode
import kaist.cilab.parser.corpusconverter.sejong2treebank.sejongtree.ParseTree
import kaist.cilab.parser.corpusconverter.sejong2treebank.sejongtree.TreeNode
import kaist.cilab.parser.dependency.DNode
import kaist.cilab.parser.dependency.DTree
import kaist.cilab.parser.psg2dg.Converter
import kr.bydelta.koala.DependencyTag
import kr.bydelta.koala.POS
import kr.bydelta.koala.PhraseTag
import kr.bydelta.koala.correctVerbApply
import kr.bydelta.koala.data.DepEdge
import kr.bydelta.koala.data.Morpheme
import kr.bydelta.koala.data.SyntaxTree
import kr.bydelta.koala.data.Word
import kr.bydelta.koala.proc.CanParseDependency
import kr.bydelta.koala.proc.CanParseSyntax
import kr.bydelta.koala.proc.CanSplitSentence
import kr.bydelta.koala.proc.CanTagOnlyAParagraph
import java.io.File
import java.util.*
import kotlin.collections.ArrayList

/**
 * 한나눔 문장분리기의 KoalaNLP Wrapper입니다.
 *
 * @since 1.x
 */
class SentenceSplitter : CanSplitSentence {
    /** 한나눔 분석 Workflow **/
    private val workflow: Workflow by lazy {
        val workflow = Workflow()
        val basePath = Dictionary.extractResource()

        workflow.appendPlainTextProcessor(SentenceSegmentor(),
                basePath + File.separator + "conf" + File.separator + "SentenceSegment.json")
        workflow.appendPlainTextProcessor(InformalSentenceFilter(),
                basePath + File.separator + "conf" + File.separator + "InformalSentenceFilter.json")
        workflow.activateWorkflow(false)

        workflow
    }

    /**
     * 주어진 문단 [text]를 문장단위로 분리합니다.
     *
     * @since 1.x
     * @param text 문장단위로 분리할 String.
     * @return 문장단위로 분리된 String의 [List].
     */
    override fun sentences(text: String): List<String> =
            if (text.isBlank()) emptyList()
            else {
                workflow.analyze(text)

                val buffer = mutableListOf<String>()
                var outOfResult = false

                while (!outOfResult) {
                    val sent = workflow.getResultOfSentence(SENTENCE_TYPE)
                    if (sent != null) {
                        buffer += sent.sentence
                    }

                    outOfResult = sent == null || sent.isEndOfDocument
                }

                buffer.toList()
            }

    /** static fields */
    companion object {
        /** 한나눔의 빈 문장 */
        @JvmStatic
        private val SENTENCE_TYPE = PlainSentence(0, 0, false)
    }
}


/**
 * 한나눔 품사분석기의 KoalaNLP Wrapper입니다.
 */
class Tagger : CanTagOnlyAParagraph<Sentence>() {
    /** 한나눔 품사분석 Workflow **/
    private val workflow by lazy {
        val workflow = Workflow()
        val basePath = Dictionary.extractResource()

        workflow.appendPlainTextProcessor(SentenceSegmentor(),
                basePath + File.separator + "conf" + File.separator + "SentenceSegment.json")
        workflow.appendPlainTextProcessor(InformalSentenceFilter(),
                basePath + File.separator + "conf" + File.separator + "InformalSentenceFilter.json")

        workflow.setMorphAnalyzer(SafeChartMorphAnalyzer(),
                basePath + File.separator + "conf" + File.separator + "ChartMorphAnalyzer.json",
                basePath + File.separator)
        workflow.appendMorphemeProcessor(UnknownProcessor(),
                basePath + File.separator + "conf" + File.separator + "UnknownMorphProcessor.json")

        workflow.setPosTagger(HMMTagger(),
                basePath + File.separator + "conf" + File.separator + "HmmPosTagger.json",
                basePath + File.separator)
        workflow.activateWorkflow(false)
        workflow
    }

    /**
     * 변환되지않은, [text]의 분석결과 [List]<kaist.cilab.jhannanum.common.communication.Sentence>를 반환합니다.
     *
     * @since 1.x
     * @param text 분석할 String.
     * @return 원본 분석기의 결과인 문장의 목록입니다.
     */
    override fun tagParagraphOriginal(text: String): List<Sentence> =
            if (text.isBlank()) emptyList()
            else {
                try {
                    synchronized(workflow) {
                        workflow.analyze(text)

                        val buffer = mutableListOf<Sentence>()
                        var outOfResult = false

                        while (!outOfResult) {
                            val sent = workflow.getResultOfSentence(SENTENCE_TYPE)
                            if (sent != null) {
                                buffer += sent
                            }

                            outOfResult = sent == null || sent.isEndOfDocument
                        }

                        buffer.toList()
                    }
                } catch (e: Throwable) {
                    throw e
                }
            }

    /**
     * kaist.cilab.jhannanum.common.communication.Sentence 타입의 분석결과 [result]를 변환,
     * [kr.bydelta.koala.data.Sentence]를 구성합니다.
     *
     * @since 1.x
     * @param result 변환할 분석결과.
     * @return 변환된 [kr.bydelta.koala.data.Sentence] 객체
     */
    override fun convertSentence(result: Sentence): kr.bydelta.koala.data.Sentence = Tagger.convert(result)


    /** static fields */
    companion object {
        /** 한나눔의 빈 문장 */
        @JvmStatic
        private val SENTENCE_TYPE = Sentence(0, 0, false)

        /**
         * kaist.cilab.jhannanum.common.communication.Sentence 타입의 분석결과 [result]를 변환,
         * [kr.bydelta.koala.data.Sentence]를 구성합니다.
         *
         * @since 1.x
         * @param result 변환할 분석결과.
         * @return 변환된 [kr.bydelta.koala.data.Sentence] 객체
         */
        @JvmStatic
        fun convert(result: Sentence): kr.bydelta.koala.data.Sentence {
            return kr.bydelta.koala.data.Sentence(
                    result.eojeols.zip(result.plainEojeols).map {
                        val (eojeol, plain) = it

                        Word(
                                plain,
                                eojeol.morphemes.zip(eojeol.tags).map { pair ->
                                    val (morph, tag) = pair
                                    Morpheme(morph, tag.toSejongPOS(), tag)
                                }
                        )
                    }
            )
        }
    }
}

/**
 * 한나눔 ChartMorphAnalyzer를 개량한 클래스.
 *
 * * 사용자 사전을 동적으로 불러올 수 있도록 수정하기 위해서, 전체 클래스의 복제가 필요함.
 * * 주요 변수들이 'private'으로 선언되어 있어, ChartMorphAnalyzer를 상속하여 수정할 수 없음
 * * 모델의 Path를 임시 폴더로 수정해야 함.
 *
 * 원본 파일은 `kaist.cilab.jhannanum.plugin.major.morphanalyzer.impl.ChartMorphAnalyzer`이며, 이 class 코드의 저작권은 한나눔 개발팀에 있음.
 *
 * @since 1.x
 */
internal class SafeChartMorphAnalyzer : MorphAnalyzer {
    /** 분석된 어절 목록 */
    private val eojeolList = LinkedList<Eojeol>()
    /** 후처리 기능 */
    private val postProc = PostProcessor()
    /** 형태소 차트 분석 */
    private val chart: MorphemeChart by lazy {
        synchronized(Dictionary) {
            val simti = Simti()
            simti.init()

            MorphemeChart(Dictionary.tagSet, Dictionary.connection,
                    Dictionary.systemDic, Dictionary.userDic, Dictionary.numAutomata, simti, eojeolList)
        }
    }

    override fun shutdown() {}

    /** 어절 분석 */
    private fun processEojeol(plainEojeol: String): Array<Eojeol> {
        val analysis = Dictionary.analyzedDic.get(plainEojeol)
        this.eojeolList.clear()

        if (analysis != null) {
            val st = StringTokenizer(analysis, "^")

            while (st.hasMoreTokens()) {
                val (morphemes, tags) = st.nextToken()
                        .split("[+/]".toRegex()).dropLastWhile { it.isEmpty() }
                        .chunked(2) { it[0] to it[1] }.unzip()

                this.eojeolList.add(Eojeol(morphemes.toTypedArray(), tags.toTypedArray()))
            }
        } else {
            try{
                this.chart.init(plainEojeol)
                this.chart.analyze()
                this.chart.getResult()
            }catch (_:Exception){
                this.eojeolList.clear()
                this.eojeolList.add(Eojeol(arrayOf(plainEojeol), arrayOf("nqq")))
            }
        }

        return if (eojeolList.isEmpty() && plainEojeol.isNotEmpty()) arrayOf(Eojeol(arrayOf(plainEojeol), arrayOf("f")))
            else this.eojeolList.toTypedArray()
    }

    /** 분석 수행 **/
    override fun morphAnalyze(ps: PlainSentence): SetOfSentences {
        val st = StringTokenizer(ps.sentence, " \t")
        val eojeolNum = st.countTokens()
        val plainEojeolArray = ArrayList<String>(eojeolNum)
        val eojeolSetArray = ArrayList<Array<Eojeol>>(eojeolNum)

        while (st.hasMoreTokens()) {
            val plainEojeol = st.nextToken()
            plainEojeolArray.add(plainEojeol)
            eojeolSetArray.add(this.processEojeol(plainEojeol))
        }

        return this.postProc.doPostProcessing(SetOfSentences(ps.documentID, ps.sentenceID,
                ps.isEndOfDocument, plainEojeolArray, eojeolSetArray)) ?: throw IllegalStateException()
    }

    /** 초기화 **/
    override fun initialize(configFile: String) {
        initialize(configFile, Configuration.hanBaseDir)
    }

    /** 초기화 **/
    override fun initialize(configFile: String?, dummy: String?) {
        Dictionary.loadDictionary()
    }
}

/**
 * 한나눔 구문구조 및 의존구문 분석기
 *
 * ## 참고
 * **구문구조 분석**은 문장의 구성요소들(어절, 구, 절)이 이루는 문법적 구조를 분석하는 방법입니다.
 * 예) '나는 밥을 먹었고, 영희는 짐을 쌌다'라는 문장에는
 * 2개의 절이 있습니다
 * * 나는 밥을 먹었고
 * * 영희는 짐을 쌌다
 * 각 절은 3개의 구를 포함합니다
 * * 나는, 밥을, 영희는, 짐을: 체언구
 * * 먹었고, 쌌다: 용언구
 *
 * 아래를 참고해보세요.
 * * [Word.getPhrase] 어절이 직접 속하는 가장 작은 구구조 [SyntaxTree]를 가져오는 API
 * * [kr.bydelta.koala.data.Sentence.getSyntaxTree] 전체 문장을 분석한 [SyntaxTree]를 가져오는 API
 * * [SyntaxTree] 구구조를 저장하는 형태
 * * [PhraseTag] 구구조의 형태 분류를 갖는 Enum 값
 *
 * **의존구조 분석**은 문장의 구성 어절들이 의존 또는 기능하는 관계를 분석하는 방법입니다.
 * 예) '나는 밥을 먹었고, 영희는 짐을 쌌다'라는 문장에는
 * 가장 마지막 단어인 '쌌다'가 핵심 어구가 되며,
 * * '먹었고'가 '쌌다'와 대등하게 연결되고
 * * '나는'은 '먹었고'의 주어로 기능하며
 * * '밥을'은 '먹었고'의 목적어로 기능합니다.
 * * '영희는'은 '쌌다'의 주어로 기능하고,
 * * '짐을'은 '쌌다'의 목적어로 기능합니다.
 *
 * 아래를 참고해보세요.
 * * [Word.getDependentEdges] 어절이 직접 지배하는 하위 의존구조 [DepEdge]의 목록을 가져오는 API
 * * [Word.getGovernorEdge] 어절이 지배당하는 상위 의존구조 [DepEdge]를 가져오는 API
 * * [kr.bydelta.koala.data.Sentence.getDependencies] 전체 문장을 분석한 의존구조 [DepEdge]의 목록을 가져오는 API
 * * [DepEdge] 의존구조를 저장하는 형태
 * * [PhraseTag] 의존구조의 형태 분류를 갖는 Enum 값 (구구조 분류와 같음)
 * * [DependencyTag] 의존구조의 기능 분류를 갖는 Enum 값
 *
 * ## 사용법 예제
 *
 * ### Kotlin
 * ```kotlin
 * // 문장에서 바로 분석할 때
 * val parser = Parser()
 * val sentences = parser.analyze("문장 2개입니다. 결과는 목록이 됩니다.") // 또는 parser("문장 2개입니다. 결과는 목록이 됩니다.")
 *
 * // 타 분석기에서 분석한 다음 이어서 분석할 때
 * val taggedSentence: Sentence = ...
 * val sentence = parser.analyze(taggedSentence) // 또는 parser(taggedSentence)
 *
 * val taggedSentList: List<Sentence> = ...
 * val sentences = parser.analyze(taggedSentList) // 또는 parser(taggedSentList)
 * ```
 *
 * ### Scala + [koalanlp-scala](https://koalanlp.github.io/scala-support/)
 * ```scala
 * import kr.bydelta.koala.Implicits._
 * // 문장에서 바로 분석할 때
 * val parser = new Parser()
 * val sentences = parser.analyze("문장 2개입니다. 결과는 목록이 됩니다.") // 또는 parser("문장 2개입니다. 결과는 목록이 됩니다.")
 *
 * // 타 분석기에서 분석한 다음 이어서 분석할 때
 * val taggedSentence: Sentence = ...
 * val sentence = parser.analyze(taggedSentence) // 또는 parser(taggedSentence)
 *
 * val taggedSentList: java.util.List[Sentence] = ...
 * val sentences = parser.analyze(taggedSentList) // 또는 parser(taggedSentList)
 * ```
 *
 * ### Java
 * ```java
 * // 문장에서 바로 분석할 때
 * Parser parser = Parser()
 * List<Sentence> sentences = parser.analyze("문장 2개입니다. 결과는 목록이 됩니다.") // 또는 parser.invoke("문장 2개입니다. 결과는 목록이 됩니다.")
 *
 * // 타 분석기에서 분석한 다음 이어서 분석할 때
 * Sentence taggedSentence = ...
 * Sentence sentence = parser.analyze(taggedSentence) // 또는 parser.invoke(taggedSentence)
 *
 * List<Sentence> taggedSentList = ...
 * List<Sentence> sentences = parser.analyze(taggedSentList) // 또는 parser.invoke(taggedSentList)
 * ```
 *
 * @since 2.0.0
 */
class Parser : CanParseDependency<Sentence>, CanParseSyntax<Sentence> {
    /**
     * 품사분석기
     */
    private val tagger by lazy { Tagger() }
    /**
     * 의존관계분석 Wrapper
     */
    private val wrapper by lazy { BerkeleyParserWrap() }
    /**
     * 의존관계분석결과 변환기.
     */
    private val converter: Converter by lazy { Converter() }

    /**
     * String [sentence]를 품사 분석하여 분석기가 받아들이는 [List]<kaist.cilab.jhannanum.common.communication.Sentence>로 변환합니다.
     *
     * @since 2.0.0
     * @param sentence 텍스트에서 변환할 문장입니다.
     * @return 분석기가 받아들일 수 있는 형태의 데이터입니다.
     */
    override fun convert(sentence: String): List<Pair<Sentence, String>> =
            tagger.tagParagraphOriginal(sentence).map { it to it.plainEojeols.joinToString(" ") }

    /**
     * 분석기의 중간 결과인 [sentence]를 조합하여 [kr.bydelta.koala.data.Sentence] 객체로 변환합니다.
     *
     * @since 2.0.0
     * @param sentence 변환할 문장입니다.
     * @return [kr.bydelta.koala.data.Sentence] 객체입니다.
     */
    override fun convert(sentence: Sentence): kr.bydelta.koala.data.Sentence = Tagger.convert(sentence)

    /** Parse의 결과인 Non-terminal Node의 목록으로부터 단어 정보를 읽어 Koala의 Word 목록으로 바꿉니다.
     *
     * *참고* 한나눔은 Parser에서 기존 분석 결과를 변형합니다. 때문에, 품사분석의 결과와 의존구문/구문분석의 결과는 일치하지 않을 수 있습니다.
     * */
    private fun List<NonterminalNode>.toWordsWith(): List<Word> = this.map {
        val terminals = it.myTerminals.map { m ->
            Morpheme(m.word.replace("-LRB-", "(").replace("-RRB-", ")"),
                    m.pos.toSejongPOS(), m.pos)
        }

        val surface = constructWordSurface(terminals)
        Word(surface, terminals)
    }

    /**
     * 분석기 처리 과정에서 잃어버린 단어 원형을 근사하여 복원합니다.
     * @since 1.x
     */
    private fun constructWordSurface(word: List<Morpheme>): String {
        var head = ""
        var wasVerb = false

        for (curr in word) {
            val tag = curr.tag
            if (tag.isEnding() && head.isNotEmpty()) {
                head = correctVerbApply(head, wasVerb, curr.surface)
                wasVerb = false
            } else {
                wasVerb = (tag.isPredicate() && tag != POS.VA) || tag == POS.XSV
                head += curr.surface
            }
        }

        return head
    }

    /** 한나눔의 구문분석 결과인 TreeNode 1개를 하위구조를 분석하여 Syntax Tree 1개로 변환합니다. */
    private fun TreeNode.rollupPhrases(terminals: Map<NonterminalNode, SyntaxTree>): SyntaxTree =
            when (this) {
                is NonterminalNode -> {
                    terminals[this] ?: // Terminal node에 포함되어 있는 경우, terminal 그대로 제공, 아니라면 새 syntax tree 구성
                    SyntaxTree(phraseTag.toETRIPhraseTag(), null,
                            children.map { it.rollupPhrases(terminals) },
                            originalLabel = phraseTag.split("-")[0])
                }
                else ->
                    throw IllegalStateException()
            }

    /** 한나눔의 구문분석 결과들을 하나의 Syntax Tree로 변환합니다. */
    private fun List<NonterminalNode>.toSyntaxTreeOn(sentence: kr.bydelta.koala.data.Sentence): SyntaxTree {
        val terminals = mapIndexed { index, nonterminalNode ->
            val terminalTree = SyntaxTree(nonterminalNode.phraseTag.toETRIPhraseTag(),
                    sentence[index], originalLabel = nonterminalNode.phraseTag.split("-")[0])
            nonterminalNode to terminalTree
        }.toMap()

        return this[0].tree.head.rollupPhrases(terminals)
    }

    /** 한나눔의 의존구문분석 결과들을 DepEdge의 목록으로 변환합니다. */
    private fun Array<DNode>.toDepEdgesOn(sentence: kr.bydelta.koala.data.Sentence): List<DepEdge> =
            this.map { node ->
                val depTag = node.getdType().toETRIDepTag()
                val pTag = node.correspondingPhrase.phraseTag.toETRIPhraseTag()
                val thisWord = sentence[node.wordIdx]
                val headWord = sentence.getOrNull(node.head?.wordIdx ?: -1)

                DepEdge(headWord, thisWord, pTag, depTag,
                        originalLabel = "${node.correspondingPhrase.phraseTag.split("-")[0]}_${node.getdType()}")
            }

    /**
     * [item]을 분석하여 property 값을 반환합니다.
     *
     * @since 2.0.0
     * @param item 분석 단위 1개입니다.
     * @param sentence 원본 문장입니다.
     * @return 분석의 결과물입니다.
     */
    override fun attachProperty(item: Sentence, sentence: String): kr.bydelta.koala.data.Sentence =
            if (item.eojeols.isEmpty()) kr.bydelta.koala.data.Sentence.empty
            else {
                val tree = parseTreeOf(item)
                val depTree: DTree = converter.convert(tree)
                val phrases = tree.prePreTerminalNodes
                val result = kr.bydelta.koala.data.Sentence(phrases.toWordsWith())

                result.setSyntaxTree(phrases.toSyntaxTreeOn(result))
                result.setDepEdges(depTree.nodeList.toDepEdgesOn(result))

                result
            }

    /**
     * 구문분석트리를 구성함.
     *
     * @param sentence 분석할 한나눔 문장.
     * @return 구문분석트리.
     */
    private fun parseTreeOf(sentence: Sentence): ParseTree =
            if (sentence.eojeols.isEmpty()) ParseTree("", "", 0, true)
            else
                ParseTree(
                        sentence.plainEojeols.joinToString(" "),
                        converter.StringforDepformat(
                                Converter.functionTagReForm(
                                        wrapper.parseForced(sentence.withEncodedParen())
                                )
                        ), 0, true)

    /**
     * 분석 오류를 발생시키는 괄호 ()를 LRB, RRB로 변경.
     */
    private fun Sentence.withEncodedParen(): Sentence {
        eojeols.filterNotNull().forEach {
            val morphs = it.morphemes
            morphs.forEachIndexed { index, m ->
                if (m.matches("^.*[()]+.*$".toRegex())) {
                    morphs[index] = m.replace("(", "-LRB-").replace(")", "-RRB-")
                }
            }
        }

        return this
    }

    /**
     * Sentence [sentence]를 해체하여 분석기가 받아들이는 kaist.cilab.jhannanum.common.communication.Sentence로 변환합니다.
     *
     * @since 2.0.0
     * @param sentence 변환할 문장입니다.
     * @return 분석기가 받아들일 수 있는 형태의 데이터입니다.
     */
    override fun convert(sentence: kr.bydelta.koala.data.Sentence): Sentence {
        val (plainEojeols, eojeols) = sentence.map { word ->
            val (morphs, tags) = word.map { it.surface to it.tag.fromSejongPOS() }.unzip()
            word.surface to Eojeol(morphs.toTypedArray(), tags.toTypedArray())
        }.unzip()

        return Sentence(0, 0, true, plainEojeols.toTypedArray(), eojeols.toTypedArray())
    }
}


/**
 * 한나눔 BerkeleyParserWrapper를 개량한 Class.
 * - Scala에 맞게 Logic 수정.
 * - 한나눔 품사분석결과에서 시작하도록 수정.
 * - 모델의 경로를 임시 디렉터리가 되도록 수정.
 *
 * 원본의 Copyright: KAIST 한나눔 개발팀.
 */
internal class BerkeleyParserWrap {
    private val parser: CoarseToFineMaxRuleParser
        get() {
            val p = CoarseToFineMaxRuleParser(BerkeleyParserWrap.pData.grammar, BerkeleyParserWrap.pData.lexicon,
                    BerkeleyParserWrap.opts.unaryPenalty, BerkeleyParserWrap.opts.finalLevel,
                    BerkeleyParserWrap.opts.viterbi, false, false, BerkeleyParserWrap.opts.accurate,
                    false, BerkeleyParserWrap.opts.useGoldPOS, true)
            p.binarization = BerkeleyParserWrap.pData.binarization
            return p
        }

    fun parseForced(data: Sentence): String {
        val testSentence = MorphemeAnalyzerWrap.getSpacedResult(data)

        return Trees.PennTreeRenderer.render(
                TreeAnnotations.unAnnotateTree(
                        this.parser.getBestConstrainedParse(testSentence, null, null),
                        false)
        )
    }

    /** Static fields **/
    companion object {
        @JvmStatic
        private val opts: GrammarTester.Options =
            OptionParser(GrammarTester.Options::class.java)
                    .parse(arrayOf("-in",
                            Dictionary.extractResource() + "/models/parser/KorGrammar_BerkF_ORIG".replace("/", File.separator)
                    ), true) as GrammarTester.Options

        @JvmStatic
        private val pData: ParserData by lazy {
            val p = ParserData.Load(opts.inFileName)
            Numberer.setNumberers(p.numbs)
            p.grammar.splitRules()
            p
        }
    }
}


/**
 * 한나눔 HanNanumMorphAnalWrapper를 개량한 클래스.
 * - Scala에 맞게 수정.
 * - Parsing 과정에서, Tagging을 다시 실시하지 않도록 수정.
 *
 * 원본의 Copyright: KAIST 한나눔 개발팀.
 */
internal object MorphemeAnalyzerWrap {
    @JvmStatic
    private val TOKENS = "^(\\+*[^+]*)/([a-z]+)\\+".toRegex()

    /**
     * 띄어쓰기 된 token으로 변환
     */
    @JvmStatic
    fun getSpacedResult(sent: Sentence) = this.getAnalysisResult(sent).flatMap { it.tokenList.map { w -> w.word } }

    /**
     * 파서가 이해할 수 있게 분석 결과 변환
     */
    @JvmStatic
    fun getAnalysisResult(raw: Sentence): List<kaist.cilab.parser.berkeleyadaptation.Eojeol> {
        /******* 수정된 부분 시작 *******/
        val txt = raw.plainEojeols
        val result = this.modifySenLevelPOSResult(raw.toString())

        val tok = StringTokenizer(result, "\n\r")
        val ret = mutableListOf<kaist.cilab.parser.berkeleyadaptation.Eojeol>()


        var charOffset = 0
        for (word in txt) {
            val e = kaist.cilab.parser.berkeleyadaptation.Eojeol()
            e.offset = charOffset
            e.origEojeol = tok.nextToken()

            /******** 수정된 부분 끝 ********/

            val analResult =
                    if (e.origEojeol.endsWith("에서")) {
                        tok.nextToken()
                        e.origEojeol.substring(0, e.origEojeol.length - 2) + "/ncn+에서/jco"
                    } else
                        tok.nextToken().trim()

            var eachAnal = "$analResult+"
            val tokTmp = ArrayList<kaist.cilab.parser.berkeleyadaptation.Eojeol.Token>()
            while (eachAnal.isNotEmpty()) {

                val match = TOKENS.find(eachAnal)
                if (match == null)
                    throw IllegalStateException()
                else {
                    val token = match.groupValues[1]
                    val pos = match.groupValues[2]

                    eachAnal = eachAnal.substring(token.length + pos.length + 2)

                    val t = e.Token()
                    t.word = token
                    t.pos = if (token == "부터") "jca" else pos

                    tokTmp.add(t)
                }
            }

            e.tokenList = tokTmp.toTypedArray()

            this.integrateSamePOS(e, "n")
            this.integrateSamePOS(e, "j")
            e.eojeolIdx = ret.size
            ret += e

            charOffset += word.length + 1
        }

        return ret
    }

    /**
     * 동일 품사 결과 통합
     * (코드 간결하게 재작성됨)
     */
    @JvmStatic
    private fun integrateSamePOS(e: kaist.cilab.parser.berkeleyadaptation.Eojeol, pos: String) {
        val tokens = mutableListOf<kaist.cilab.parser.berkeleyadaptation.Eojeol.Token>()
        var merged = false

        for (token in e.tokenList) {
            if (token.pos.startsWith(pos)) {
                if (merged) {
                    tokens.last().word += token.word
                } else {
                    tokens.add(token)
                }

                merged = true
            } else {
                tokens.add(token)

                merged = false
            }
        }

        e.tokenList = tokens.toTypedArray()
    }

    /** 특수 분석결과 변환 (코드 재작성) */
    @JvmStatic
    private fun modifySenLevelPOSResult(result: String): String =
            result.replace("가/pvg+아/ecx+지/px", "가지/pvg")
                    .replace("입/pvg+니다/ef", "이/jp+ㅂ니다/ef")
                    .replace("일/pvg+ㅂ니다/ef", "이/jp+ㅂ니다/ef")
                    .replace("에서/jca+는/jxc", "에서는/jca")
}
