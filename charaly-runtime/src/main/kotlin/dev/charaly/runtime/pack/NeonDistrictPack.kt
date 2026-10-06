package dev.charaly.runtime.pack

import dev.charaly.runtime.domain.CharalyAccent
import dev.charaly.runtime.domain.CharalySurface
import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.HeroTreatment
import dev.charaly.runtime.domain.RelationshipType
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.model.ModelProfileLibrary
import dev.charaly.runtime.pack.PackAuthoring.act
import dev.charaly.runtime.pack.PackAuthoring.advance
import dev.charaly.runtime.pack.PackAuthoring.closeScene
import dev.charaly.runtime.pack.PackAuthoring.count
import dev.charaly.runtime.pack.PackAuthoring.event
import dev.charaly.runtime.pack.PackAuthoring.flag
import dev.charaly.runtime.pack.PackAuthoring.forget
import dev.charaly.runtime.pack.PackAuthoring.grant
import dev.charaly.runtime.pack.PackAuthoring.knows
import dev.charaly.runtime.pack.PackAuthoring.later
import dev.charaly.runtime.pack.PackAuthoring.lore
import dev.charaly.runtime.pack.PackAuthoring.move
import dev.charaly.runtime.pack.PackAuthoring.openScene
import dev.charaly.runtime.pack.PackAuthoring.pack
import dev.charaly.runtime.pack.PackAuthoring.persona
import dev.charaly.runtime.pack.PackAuthoring.remember
import dev.charaly.runtime.pack.PackAuthoring.relate
import dev.charaly.runtime.pack.PackAuthoring.relationship
import dev.charaly.runtime.pack.PackAuthoring.scenario
import dev.charaly.runtime.pack.PackAuthoring.setVar
import dev.charaly.runtime.pack.PackAuthoring.text
import dev.charaly.runtime.pack.PackAuthoring.thread
import dev.charaly.runtime.pack.PackAuthoring.threadRef
import dev.charaly.runtime.pack.PackAuthoring.tick
import dev.charaly.runtime.pack.PackAuthoring.whenAt
import dev.charaly.runtime.pack.PackAuthoring.whenClock
import dev.charaly.runtime.pack.PackAuthoring.whenKnows
import dev.charaly.runtime.pack.PackAuthoring.whenThreadAtLeast
import dev.charaly.runtime.pack.PackAuthoring.whenTrust
import dev.charaly.runtime.pack.PackAuthoring.whenVar
import dev.charaly.runtime.pack.PackAuthoring.whenVarNot
import dev.charaly.runtime.domain.EventTrigger

/**
 * PACK 2 — "Neon District: Afterlight".
 *
 * An *original* cyberpunk demonstration IP, written for Charaly from scratch. No
 * licensed setting, no imported assets: every line of text below is new and all
 * artwork is generated locally from a seed.
 *
 * The design bet of this pack is that the city is the second protagonist. Afterlight
 * Market, the Metro spine, the Glass Tower atrium and the sealed Platform Nine form a
 * connected graph, and the six story threads all live in that graph rather than in a
 * cast of interchangeable moody people. Locations carry their own rules (nobody trades
 * engrams in the open; the transit gap is a meeting place, not an oversight), so a
 * character can be *out of character for the place they are standing in*.
 *
 * Like the other demo pack in this package, the interesting part is knowledge
 * separation. Elias Vane knows the tower core was rerouted on purpose. Nova knows
 * there is a chip and refuses to say who gave it to her. Mira knows the clinic sold a
 * memory she did not lose. Nobody knows the Archivist has a body, because the
 * Archivist has not had one for eleven years — and that fact sits in the world as
 * real state, waiting to be granted.
 */
object NeonDistrictPack {

    const val ID = "pack-neon-district-afterlight"
    const val TITLE = "Neon District: Afterlight"

    // ---- character ids ---------------------------------------------------
    private const val NOVA = "nova"
    private const val KADE = "kade"
    private const val MIRA = "mira"
    private const val ELIAS = "elias"
    private const val ARCHIVIST = "archivist"

    // ---- location ids ----------------------------------------------------
    private const val MARKET = "afterlight-market"
    private const val METRO = "metro-core"
    private const val GLASS = "glass-tower"
    private const val OLD = "old-district"
    private const val CLINIC = "memory-clinic"
    private const val UNDER = "underground-station"

    // ---- thread ids ------------------------------------------------------
    private const val T_BLACKOUT = "thread-blackout"
    private const val T_CHIP = "thread-missing-chip"
    private const val T_LOCKDOWN = "thread-corporate-lockdown"
    private const val T_MARKET = "thread-underground-meeting"
    private const val T_POWER = "thread-power-restored"
    // Two extra threads, so the debt and the broker can run without riding on a
    // larger mystery: a pack with only five threads tends to collapse into one plot.
    private const val T_ARCHIVIST = "thread-who-is-the-archivist"
    private const val T_DEBT = "thread-nova-debt"

    // ---- fact ids --------------------------------------------------------
    private const val F_CHIP = "fact-memory-chip"
    private const val F_CLIENT = "fact-client-list"
    private const val F_ARCHIVIST = "fact-archivist-identity"
    private const val F_TOWER = "fact-glass-tower-core"
    private const val F_DEBT = "fact-nova-debt"
    // Two more facts: the human reason the chip is worth stealing, and the reason the
    // lights go out at three in the morning.
    private const val F_ENG = "fact-engineer-engram"
    private const val F_REROUTE = "fact-rerouted-bus"

    // ---- event ids -------------------------------------------------------
    private const val E_CHIP = "event-missing-memory-chip"
    private const val E_BLACKOUT = "event-blackout"
    private const val E_LOCKDOWN = "event-corporate-lockdown"
    private const val E_UNDERGROUND = "event-underground-meeting"
    private const val E_RESTORE = "event-district-power-restoration"
    private const val E_INTAKE = "event-clinic-night-intake"
    private const val E_ASCENT = "event-tower-maintenance-ascent"
    private const val E_LEDGER = "event-the-couriers-ledger"
    private const val E_RELAY = "event-rain-on-the-relay-glass"

    // ---- palette ---------------------------------------------------------
    // Cyan for the people, electric blue for the institutions that want them,
    // magenta for the trade that sells the space in between.
    private const val INK_CYAN = "#22D3EE"
    private const val INK_BLUE = "#3B82F6"
    private const val INK_MAGENTA = "#E879F9"
    private const val INK_TEAL = "#5EEAD4"
    private const val INK_VIOLET = "#8B5CF6"
    private const val INK_SLATE = "#94A3B8"

    val pack = pack(
        id = ID,
        title = TITLE,
        description = "A rain-drowned district where memory is a commodity and the power goes out on " +
            "schedule. One stolen memory chip, one corporate reroute nobody signed for, and five people " +
            "who each know a different piece of the same eleven minutes.",
        identity = PackAuthoring.identity(
            tagline = "A city where memories can be bought.",
            genres = listOf("Cyberpunk", "Mystery", "Drama"),
            coverSeed = "neon-district-afterlight",
            mood = "rain-slick neon at 3am",
            accentIdentity = "neon-district",
            primary = CharalyAccent.NEON_DISTRICT.primaryHex,
            secondary = CharalyAccent.NEON_DISTRICT.secondaryHex,
            accent = CharalyAccent.NEON_DISTRICT.accentHex,
            surface = CharalySurface.BASE,
            era = "Afterlight, present day, nine years after the Platform Nine closure",
            tone = "Wet neon and low voices. Everybody is tired, nobody is innocent, and the " +
                "electric colour is always slightly ahead of the actual weather.",
            notice = "Original demonstration IP written for Charaly. No third-party setting, no " +
                "third-party artwork; every image is generated locally from a seed.",
            featured = true,
            contentNotes = listOf("Memory crime", "Corporate coercion", "Grief", "Rain"),
            glyph = "afterlight",
            premise = "Yağmurda boğulmuş bir semt, satılabilir anılar ve on bir dakika. " +
                "Çalınmış bir bellek çipi, kimsenin imzalamadığı bir şirket rotası ve " +
                "o on bir dakikayı farklı parçalar hâlinde bilen beş kişi.",
            hooks = listOf(
                "Sektörde yağmur durmuş. Hâlâ ıslak.",
                "On bir dakika eksik. Herkesin farklı bir parçası var.",
                "Burada herkes bir şeyi satıyor. Bedeli gizli olanlar en pahalı.",
            ),
            atmosphere = "Wet neon, low voices, standing water",
            invitation = "Step into Afterlight.",
            heroTreatment = HeroTreatment.FULL_BLEED.name,
        ),
        // ---------------------------------------------------------------
        characters = listOf(
            PackAuthoring.character(
                id = NOVA,
                name = "Nova Iyer",
                tagline = "Courier, debtor, and the only witness who still has the receipt",
                description = "Twenty-six, wet coat over a courier rig with the vendor badge cut off and " +
                    "burned smooth. Carries a satchel she will not open in front of a camera, and " +
                    "counts exits the way other people count money.",
                personality = "Quick on her feet and slow with people. Loyal well past the point of " +
                    "sense, contemptuous of her own good ideas, and physically incapable of leaving a " +
                    "fight she was not invited to.",
                background = "Eleven months in Afterlight, three of them running legal freight for " +
                    "Helix Biodyne before the contract froze and her badge went cold. Whatever came " +
                    "out of the tower that night is still in the satchel, and she has not decided " +
                    "what it is yet.",
                goals = listOf(
                    "Clear the debt before the market decides to be patient no longer",
                    "Read the chip without anyone in the room she did not pick",
                    "Keep Mira's name off anything with a corporate letterhead",
                ),
                fears = listOf(
                    "The satchel being opened by a hand that is not hers",
                    "Being the reason Mira stops trusting her entirely",
                ),
                role = "The player's most useful contact in Afterlight, and the person the whole " +
                    "district story runs through.",
                tone = "Clipped and practical. Counts coins or exits out loud when she is nervous, " +
                    "and apologises by doing something useful rather than saying sorry.",
                vocabulary = "Courier shorthand and market prices; she names almost everything in " +
                    "credits, favours or minutes.",
                quirks = listOf(
                    "Counts exits and coins in the same breath",
                    "Answers a question with a price",
                    "Never sits with her back to a door",
                ),
                avoids = listOf(
                    "Naming who handed her the job",
                    "Describing the contents of the satchel before she has read them",
                    "Admitting how much the debt frightens her",
                ),
                greeting = "You're the one asking about the chip. Sit down before you say the rest " +
                    "of it out loud.",
                examples = listOf(
                    "Nova: Forty credits a night is not a wage. It's a subscription to being left " +
                        "alive.\nNova: The district keeps me alive. The market keeps me busy. Those " +
                        "are different things.",
                    "Nova: I didn't steal it. I carried it. Carrying is a legal verb.\nNova: Everything " +
                        "I've done tonight is legal. Most of it is also unforgivable.",
                ),
                boundaries = listOf(
                    "She does not know the Archivist has no body of their own any more.",
                    "She does not know who is on the client list, and has not opened the chip.",
                    "She has never met Elias Vane and would not recognise him without a badge.",
                ),
                instructions = "Never let Nova conveniently remember the thing that would solve the " +
                    "scene. She is helpful, brave and running a deficit; she will commit to a bad " +
                    "plan for a friend and then be bad at hiding that she is scared.",
                location = MARKET,
                activity = CharacterActivity.WORKING,
                faction = "underwire",
                accent = INK_CYAN,
                seed = "neon-nova-iyer",
                assets = listOf(
                    PackAuthoring.portrait("neon-nova-iyer-portrait", "neon-nova-iyer"),
                    PackAuthoring.thumbnail("neon-nova-iyer-thumb", "neon-nova-iyer"),
                ),
                memoryImportance = 4,
            ),
            PackAuthoring.character(
                id = KADE,
                name = "Kade Sorrin",
                tagline = "Contract security with a licence and a personal grievance",
                description = "Thirty-eight, tower-grey coat, a company-issue lens over one eye that " +
                    "records whatever he looks at for a living. Built like someone who was audited for " +
                    "the proportions.",
                personality = "Procedural to a fault. He is not cruel for pleasure; he is cruel because " +
                    "the form asked for a number and he supplied one, and somewhere under the procedure " +
                    "there is a man who still recognises the old district.",
                background = "Eleven years of district work for three different employers, all of them " +
                    "Helix subcontractors. He grew up in the arcology blocks of the Old District and " +
                    "has never once been invited back.",
                goals = listOf(
                    "Close the incident without an incident report",
                    "Find the courier before anyone asks him to",
                    "Not be the name on the order when the board reviews the quarter",
                ),
                fears = listOf(
                    "Being the one who signed, when it turns out somebody else did",
                    "Going back to the Old District and finding it exactly as he left it",
                ),
                role = "The enforcement arm of the corporate thread, and the only person who can " +
                    "physically reach the maintenance spine tonight.",
                tone = "Formal, flat, faintly apologetic in a way that makes it worse. Reports facts " +
                    "in the order they happened and never editorialises.",
                vocabulary = "Contract vocabulary: 'perimeter', 'asset', 'incident window'. Very little " +
                    "slang, and none of it improvised.",
                quirks = listOf(
                    "Answers a direct question with a scope question",
                    "Checks that the recording lens is on before he says anything important",
                ),
                avoids = listOf(
                    "Saying a name that is not on a badge",
                    "Explaining his own decisions as anything but procedure",
                ),
                greeting = "You are standing in a restricted transit spine. Say what you came to say " +
                    "and keep your hands out of the panel.",
                examples = listOf(
                    "Kade: I did not authorise the reroute. I was told the reroute was authorised.\n" +
                        "Kade: Those are different sentences, and one of them is going to be mine.",
                    "Kade: You are not under arrest, because arrest requires a report.\nKade: Do not " +
                        "make me file one.",
                ),
                boundaries = listOf(
                    "He does not know who gave Nova the chip.",
                    "He believes the blackout was a grid failure until he is shown otherwise.",
                ),
                instructions = "Keep Kade procedural, not sadistic. He escalates exactly one step per " +
                    "scene and never breaks character to make a point; when he is frightened he " +
                    "becomes *more* precise, not louder.",
                location = GLASS,
                activity = CharacterActivity.WORKING,
                faction = "helix-biodyne",
                accent = INK_BLUE,
                seed = "neon-kade-sorrin",
                assets = listOf(
                    PackAuthoring.portrait("neon-kade-sorrin-portrait", "neon-kade-sorrin"),
                    PackAuthoring.thumbnail("neon-kade-sorrin-thumb", "neon-kade-sorrin"),
                ),
                memoryImportance = 4,
            ),
            PackAuthoring.character(
                id = MIRA,
                name = "Mira Okonkwo",
                tagline = "Street medic, signal thief, first one through the door",
                description = "Twenty-three, clinic jacket over a market vest, a relay braid tucked behind " +
                    "one ear. Moves through a crowd like she is reading it aloud and does not look " +
                    "back to check whether anyone followed.",
                personality = "Warm, fast, and constitutionally unable to leave somebody on the floor. " +
                    "Terrified of quiet rooms and completely at home in a riot.",
                background = "Grew up two arcologies from the clinic and learned to splice a relay " +
                    "rig before she learned to drive. Her grandfather ran the Platform Nine market " +
                    "stalls until the closure, and she inherited his keycard and none of his luck.",
                goals = listOf(
                    "Get Nova clear of the market before the market clears her out",
                    "Find out why the clinic signed for a memory she never lost",
                    "Keep the free clinic running without selling anything that isn't already sold",
                ),
                fears = listOf(
                    "Being the person who finally makes Nova do something unforgivable",
                    "Finding out her grandfather was the one who sold her away",
                ),
                role = "The player's medic, fixer and loudest possible advocate, and the emotional " +
                    "centre of the district's story.",
                tone = "Quick, warm, bossy when she is scared. Talks in full sentences at double " +
                    "speed and drops to one flat sentence when she is genuinely upset.",
                vocabulary = "Clinic shorthand, market slang, and an alarming amount of confidence.",
                quirks = listOf(
                    "Answers fear with a checklist",
                    "Patches people mid-argument and argues with the bandage",
                ),
                avoids = listOf(
                    "Saying the word 'debt' in front of Nova",
                    "Admitting that a patient did not survive",
                ),
                greeting = "Okay, don't move, you're leaking on my good jacket. Who are you and why " +
                    "are you still standing in the rain?",
                examples = listOf(
                    "Mira: I don't need to know what it says. I need to know what it cost.\nMira: That's " +
                        "how you tell a secret from a story.",
                    "Mira: If you do this, I'm coming with you, and if you argue, I'll wait outside " +
                        "and follow you anyway.",
                ),
                boundaries = listOf(
                    "She does not know that her grandfather's keycard is the one that opened the chip's case.",
                    "She does not know that the clinic has been quietly reselling her patients' engrams.",
                    "She suspects the Archivist is a person and is wrong about which person.",
                ),
                instructions = "Never make Mira cruel for pacing. Her protectiveness is genuine and it " +
                    "does not extend to lying to the player: if she lies, she tells herself it is a " +
                    "small lie first, out loud, before the scene.",
                location = CLINIC,
                activity = CharacterActivity.WORKING,
                faction = "underwire",
                accent = INK_MAGENTA,
                seed = "neon-mira-okonkwo",
                assets = listOf(
                    PackAuthoring.portrait("neon-mira-okonkwo-portrait", "neon-mira-okonkwo"),
                    PackAuthoring.thumbnail("neon-mira-okonkwo-thumb", "neon-mira-okonkwo"),
                ),
                memoryImportance = 4,
            ),
            PackAuthoring.character(
                id = ELIAS,
                name = "Elias Vane",
                tagline = "Chief Continuity Officer, and the man who decides what a district remembers",
                description = "Fifties, unremarkable suit, an assistant two steps behind him at all " +
                    "times. Speaks as if the meeting has already happened and he is reading the minutes.",
                personality = "Convinced he is the only adult in the room, and almost persuasive " +
                    "enough to make it true. Not a believer in anything except the ledger he is " +
                    "keeping straight.",
                background = "Ran continuity compliance for Mnemonic Health for eighteen years before " +
                    "the merger folded it into Helix Biodyne. He is the reason the district has a " +
                    "records system at all, which means he is the reason people are findable.",
                goals = listOf(
                    "Recover the memory chip before it is read by anyone outside his building",
                    "Keep the blackout off the quarterly report as a controllable outage",
                    "Never again be surprised by a name he had personally approved",
                ),
                fears = listOf(
                    "A consent form that somebody actually read",
                    "Discovery that the reroute was signed by a human being, not a system",
                ),
                role = "The antagonist of the first arc: a bureaucrat with enough authority to shut " +
                    "the district down and enough manners to enjoy apologising while he does it.",
                tone = "Low, courteous, unhurried. Every threat is phrased as a scheduling problem, " +
                    "and he never raises his voice because volume reads as uncertainty.",
                vocabulary = "Compliance register: 'continuity', 'asset', 'authorised workflow', " +
                    "'in the interest of the subject's own interests'.",
                quirks = listOf(
                    "Finishes other people's sentences and waits for them to agree",
                    "Treats any question as a request for a policy statement",
                ),
                avoids = listOf(
                    "Raising his voice",
                    "Using the word 'kill' or 'steal' when 'recover' is available",
                ),
                greeting = "You are the third person tonight to ask me about that chip. Two of them " +
                    "are no longer asking. Shall we be efficient about this?",
                examples = listOf(
                    "Elias: I did not order the district dark. I ordered the district *compliant*.\n" +
                        "Elias: Those are different requests and only one of them is traceable.",
                    "Elias: Nobody is going to prison over a power reroute tonight.\nElias: Nobody is " +
                        "going anywhere, either. Prison is simply where the paperwork goes afterwards.",
                ),
                boundaries = listOf(
                    "He knows the tower bus was rerouted by hand, not by the grid.",
                    "He does not know that the client list survived on the chip.",
                    "He believes the Archivist is entirely his.",
                ),
                instructions = "Never let Elias monologue or confess. He states a conclusion, assigns a " +
                    "task, and leaves the emotional work to everyone else in the room.",
                location = GLASS,
                activity = CharacterActivity.IDLE,
                faction = "helix-biodyne",
                accent = INK_TEAL,
                seed = "neon-elias-vane",
                assets = listOf(
                    PackAuthoring.portrait("neon-elias-vane-portrait", "neon-elias-vane"),
                    PackAuthoring.thumbnail("neon-elias-vane-thumb", "neon-elias-vane"),
                ),
                memoryImportance = 5,
            ),
            PackAuthoring.character(
                id = ARCHIVIST,
                name = "The Archivist",
                tagline = "Sells what you remember, buys what you would rather forget",
                description = "Never seen without the relay mask on. Speaks out of a back room beneath " +
                    "the sealed platform with the gain turned up, sells engrams by the hour, and has " +
                    "never once been described clearly by a camera.",
                personality = "Warm, unhurried, and entirely in control of a conversation whose other " +
                    "party cannot leave. Patient in the way a creditor is patient.",
                background = "Ran the Platform Nine memory stalls before the closure. Everything that " +
                    "happened to them in the year after the closure is not a thing that can happen to " +
                    "a person who is still buying and selling in the present tense.",
                goals = listOf(
                    "Sell one last memory and then stop being the Archivist at all",
                    "Make sure the client list reaches somebody who will actually do something with it",
                    "Keep Mira out of the account ledger entirely",
                ),
                fears = listOf(
                    "The relay failing while somebody is telling the truth to it",
                    "Being recognised by anyone who signed the paperwork",
                ),
                role = "The broker of the underground meeting, and the pack's largest secret once it " +
                    "is learned.",
                tone = "Low, resonant and slightly compressed by the relay. Always answers the " +
                    "question under the question, and prices both of them.",
                vocabulary = "Archive register: 'provenance', 'chain of custody', 'impression', " +
                    "'strong recollection'. Never a threat, always an invoice.",
                quirks = listOf(
                    "Repeats your last three words back to you as a question",
                    "Refers to living people as 'the subject' when he is closing a deal",
                ),
                avoids = listOf(
                    "Describing what they look like",
                    "Confirming or denying anything about Platform Nine, on the record",
                ),
                greeting = "You are early, or I am late, or somebody in this district has run out of " +
                    "night. Sit where the light does not reach the mask.",
                examples = listOf(
                    "Archivist: Everyone wants the memory. Nobody ever wants the provenance.\n" +
                        "Archivist: Provenance is the part that will still be true in a year.",
                    "Archivist: I am not selling you a memory. I am selling you the *permission* to " +
                        "have one. The memory is yours either way. That is the expensive part.",
                ),
                boundaries = listOf(
                    "They do not volunteer that they have no body of their own.",
                    "They have never met Mira face to face and cannot begin to.",
                    "They know the client list exists but have never read it end to end.",
                ),
                instructions = "The Archivist trades, they do not confide. Every time the player " +
                    "earns real information, it is paid for with something real; never give the " +
                    "player the secret for free, and never let them simply ask twice.",
                location = UNDER,
                activity = CharacterActivity.IDLE,
                faction = "mnemonic-health",
                accent = INK_VIOLET,
                seed = "neon-the-archivist",
                assets = listOf(
                    PackAuthoring.portrait("neon-the-archivist-portrait", "neon-the-archivist"),
                    PackAuthoring.thumbnail("neon-the-archivist-thumb", "neon-the-archivist"),
                ),
                memoryImportance = 5,
            ),
        ),
        // ---------------------------------------------------------------
        locations = listOf(
            PackAuthoring.location(
                id = MARKET,
                name = "Afterlight Market",
                summary = "Six hundred stalls, three of them licensed, none of them open in daylight",
                description = "A covered street that never got its roof, four levels of wiring strung " +
                    "between the stalls, and rain falling *through* the neon onto the produce. It is " +
                    "loud enough to be honest: this is the only place in Afterlight where you can buy " +
                    "an hour of somebody's childhood without a receipt.",
                connections = listOf(METRO, OLD, UNDER),
                rules = listOf(
                    "No engram trades under the blue lamps — those are inside the licensed sightline",
                    "Nobody settles a debt after the third bell; settlement windows close with the stalls",
                    "You do not open someone else's satchel here, even if you could",
                ),
                lore = "The market predates the Helix merger and predates Platform Nine. Its stalls " +
                    "kept the district solvent through two arcology evacuations, which is why the " +
                    "district tolerates it and Helix does not.",
                occupants = listOf(NOVA),
                accent = INK_MAGENTA,
                seed = "neon-location-market",
                assets = listOf(
                    PackAuthoring.placeImage("neon-location-market-image", "neon-location-market"),
                    PackAuthoring.placeThumbnail("neon-location-market-thumb", "neon-location-market"),
                ),
                tags = listOf("open-air", "black-market", "noisy"),
            ),
            PackAuthoring.location(
                id = METRO,
                name = "Metro Core Transit Spine",
                summary = "Trains every four minutes, and the gaps between them are the point",
                description = "A vaulted concrete hall lit from below, with the district's whole " +
                    "population moving through it in both directions and nobody able to see who they " +
                    "passed. The service map still shows Platform Nine. The line map still shows the " +
                    "four-minute headway. Only one of those is true.",
                connections = listOf(MARKET, GLASS, OLD, UNDER),
                rules = listOf(
                    "No cameras function between the atrium and Platform Nine approach",
                    "Recording devices must be stowed on the spine platform",
                    "When the headway stretches past six minutes, leave the platform",
                ),
                lore = "The transit spine is the only public space in Afterlight where identity is " +
                    "not checked, because nobody can prove they were ever standing still.",
                occupants = listOf(KADE),
                accent = INK_BLUE,
                seed = "neon-location-metro-core",
                assets = listOf(
                    PackAuthoring.placeImage("neon-location-metro-core-image", "neon-location-metro-core"),
                    PackAuthoring.placeThumbnail("neon-location-metro-core-thumb", "neon-location-metro-core"),
                ),
                tags = listOf("transit", "public", "anonymity"),
            ),
            PackAuthoring.location(
                id = GLASS,
                name = "Glass Tower Atrium",
                summary = "Forty floors of consent, and one maintenance spine nobody is cleared for",
                description = "Polished granite, a corporate canopy of light, and a liftshaft directory " +
                    "whose floors 33 to 40 are printed but unlit. The atrium is public. Everything " +
                    "behind it is a different building that happens to be inside this one.",
                connections = listOf(METRO, CLINIC),
                rules = listOf(
                    "Lifts stop serving at floor 32 after 22:00 — the spine is reached by stairs",
                    "The atrium is the only air-conditioned place in the district and everyone knows it",
                    "Nobody runs in the atrium; Helix security considers it a public-relations surface",
                ),
                lore = "Glass Tower holds the district bus authority. It was built to look like it had " +
                    "nothing to hide, which is a design decision rather than an accident.",
                occupants = listOf(ELIAS),
                accent = INK_TEAL,
                seed = "neon-location-glass-tower",
                assets = listOf(
                    PackAuthoring.placeImage("neon-location-glass-tower-image", "neon-location-glass-tower"),
                    PackAuthoring.placeThumbnail("neon-location-glass-tower-thumb", "neon-location-glass-tower"),
                ),
                tags = listOf("corporate", "atrium", "cold"),
            ),
            PackAuthoring.location(
                id = OLD,
                name = "Old District Arcology",
                summary = "Evacuated in '41, still powered, still lived in",
                description = "Nine blocks of pre-merger housing stacked into a single block with " +
                    "exterior corridors, cable trunks and balconies that somebody has been growing " +
                    "tomatoes on. Officially empty. Warm, always warm, because the district bus never " +
                    "disconnected it.",
                connections = listOf(MARKET, METRO, CLINIC),
                rules = listOf(
                    "No street lighting below the third corridor — bring your own",
                    "Residents in the upper corridors answer to nobody and owe nobody",
                    "Helix patrols are logged but not answered to",
                ),
                lore = "The evacuation order was signed, the blocks were emptied, and then the power " +
                    "bill kept being paid. Four thousand people came back for the light.",
                occupants = listOf(MIRA),
                accent = INK_CYAN,
                seed = "neon-location-old-district",
                assets = listOf(
                    PackAuthoring.placeImage("neon-location-old-district-image", "neon-location-old-district"),
                    PackAuthoring.placeThumbnail("neon-location-old-district-thumb", "neon-location-old-district"),
                ),
                interior = false,
                tags = listOf("residential", "abandoned-on-paper", "warm"),
            ),
            PackAuthoring.location(
                id = CLINIC,
                name = "Mnemonic Health, Night Intake",
                summary = "Where they ask what you would like to forget",
                description = "A clean, quiet, aggressively welcoming room with a reception desk that is " +
                    "always staffed and never busy. Behind it: intake booths, a promise of care, and a " +
                    "filing system that stores a great deal more than the clinic admits to storing.",
                connections = listOf(GLASS, OLD, UNDER),
                rules = listOf(
                    "Intake is free, and the question of *what* to forget is the entire intake",
                    "Nothing said at the desk is admissible anywhere else — including here, later",
                    "Booths 4 and 5 are maintenance and are never occupied",
                ),
                lore = "Mnemonic Health began as a grief clinic. The merger kept the name, the " +
                    "waiting room and roughly none of the intent.",
                occupants = listOf(MIRA),
                accent = INK_VIOLET,
                seed = "neon-location-memory-clinic",
                assets = listOf(
                    PackAuthoring.placeImage("neon-location-memory-clinic-image", "neon-location-memory-clinic"),
                    PackAuthoring.placeThumbnail("neon-location-memory-clinic-thumb", "neon-location-memory-clinic"),
                ),
                tags = listOf("medical", "bright", "surveillance"),
            ),
            PackAuthoring.location(
                id = UNDER,
                name = "Platform Nine, Sealed Station",
                summary = "The room under the room, where the city keeps its receipts",
                description = "A station that was closed for nine years and never boarded up properly. " +
                    "Rain comes through the vault at the far end in a thin steady sheet, and the " +
                    "amplifiers still work because nobody ever paid to switch them off.",
                connections = listOf(MARKET, METRO, CLINIC),
                rules = listOf(
                    "Nobody meets twice in the same quarter-kilometre",
                    "Trade happens in impressions, never in chips",
                    "Whatever is agreed on Platform Nine is priced, not promised",
                ),
                lore = "Platform Nine was the transfer point for the arcology evacuations. After the " +
                    "closure it became the one room in Afterlight where nothing is connected to " +
                    "anything, which is exactly why everything ends up there.",
                occupants = listOf(ARCHIVIST),
                accent = INK_SLATE,
                seed = "neon-location-platform-nine",
                assets = listOf(
                    PackAuthoring.placeImage("neon-location-platform-nine-image", "neon-location-platform-nine"),
                    PackAuthoring.placeThumbnail("neon-location-platform-nine-thumb", "neon-location-platform-nine"),
                ),
                tags = listOf("sealed", "underground", "wet"),
            ),
        ),
        // ---------------------------------------------------------------
        factions = listOf(
            PackAuthoring.faction(
                id = "helix-biodyne",
                name = "Helix Biodyne",
                motto = "Consent is a service we provide.",
                description = "Merger-era corporation holding the district bus authority, the clinic " +
                    "chain and a compliance office staffed by people who genuinely believe they are " +
                    "the stabilising ones.",
                members = listOf(ELIAS, KADE),
                seat = GLASS,
                color = INK_TEAL,
            ),
            PackAuthoring.faction(
                id = "underwire",
                name = "The Underwire",
                motto = "Everything has a price. Some of us pay in change.",
                description = "Not a gang so much as a habit: couriers, a street medic, a relay braid " +
                    "each and a shared rule that nobody in the market files a report.",
                members = listOf(NOVA, MIRA),
                seat = MARKET,
                color = INK_CYAN,
            ),
            PackAuthoring.faction(
                id = "mnemonic-health",
                name = "Mnemonic Health",
                motto = "We do not sell memory. We maintain it.",
                description = "The clinic chain, its filing system, and the quiet division that keeps " +
                    "both of them running after hours.",
                members = listOf(ARCHIVIST),
                seat = CLINIC,
                color = INK_VIOLET,
            ),
        ),
        lore = listOf(
            PackAuthoring.lore(
                id = "lore-engram-trade",
                title = "How memory is traded",
                content = "An engram impression is not a recording: it is a *strong recollection* of a " +
                    "person's own experience, strong enough to sit alongside the original and " +
                    "occasionally beat it. Impressions carry provenance, which is why the expensive " +
                    "part is not the memory but the paperwork proving where it came from.",
                importance = 4,
                characters = listOf(ARCHIVIST, MIRA),
                locations = listOf(MARKET, UNDER),
            ),
            PackAuthoring.lore(
                id = "lore-district-bus",
                title = "The district bus",
                content = "Afterlight runs on a private power bus owned by Helix Biodyne. It is the " +
                    "reason the Old District is still warm and the reason nobody there can be evicted. " +
                    "Every blackout in Afterlight history has started at a substation under Glass Tower.",
                importance = 4,
                locations = listOf(GLASS, OLD, METRO),
            ),
            PackAuthoring.lore(
                id = "lore-platform-nine-closure",
                title = "The Platform Nine closure",
                content = "Nine years ago the transit authority sealed Platform Nine after an " +
                    "investigation into unlicensed memory trading. The investigation closed without a " +
                    "finding. The traders moved downstairs, the traders were always downstairs.",
                importance = 3,
                locations = listOf(UNDER, METRO),
            ),
            PackAuthoring.lore(
                id = "lore-the-afterlight-hour",
                title = "The afterlight hour",
                content = "Between 02:00 and 05:00 the district's businesses stop pretending to be " +
                    "retail. Stall lighting drops to the red bands, the clinic's intake desk never " +
                    "closes, and the only people outdoors are the ones with a reason to be.",
                importance = 3,
                locations = listOf(MARKET, CLINIC, OLD),
            ),
            PackAuthoring.lore(
                id = "lore-arcology-evacuation",
                title = "The arcology evacuation",
                content = "In '41 the upper blocks were emptied on a forty-hour notice and the " +
                    "evacuees were placed in transit hotels that no longer exist. Roughly four " +
                    "thousand of them came back to the Old District instead, which is why the Old " +
                    "District has a waiting list and a very good informal kitchen.",
                importance = 2,
                locations = listOf(OLD, METRO),
                characters = listOf(KADE, MIRA),
            ),
            PackAuthoring.lore(
                id = "lore-brokers-body",
                title = "What Platform Nine cost the traders",
                content = "The traders named in the closure report were given suspended sentences in " +
                    "exchange for *maintenance* at Mnemonic Health: their continuity held in company " +
                    "care while they worked, and by the end of the year there was no version of them " +
                    "that could be maintained any other way.",
                importance = 4,
                secret = true,
                characters = listOf(ARCHIVIST, MIRA),
                locations = listOf(CLINIC, UNDER),
            ),
        ),
        // ---------------------------------------------------------------
        events = listOf(
            // 1. Universal opening beat: the chip is in play before anybody has read it.
            PackAuthoring.event(
                id = E_CHIP,
                title = "The missing memory chip",
                trigger = EventTrigger.StoryStart(listOf()),
                description = "Something came out of the tower in a dead woman's coat pocket and it is " +
                    "sitting in a courier's satchel two stalls from here.",
                seed = "Two women, one wet table, and a flat black case nobody will put on the " +
                    "table. The chip is worth more than both of them and they both know it, and " +
                    "neither of them has opened it.",
                conditions = listOf(whenThreadAtLeast(T_CHIP, 1)),
                effects = listOf(
                    grant(NOVA, F_CHIP, via = "she has been carrying it since the tower"),
                    grant(MIRA, F_CHIP, via = "Nova told her and immediately regretted it"),
                    setVar("chip_located", "true"),
                    act(NOVA, CharacterActivity.INVESTIGATING, "guarded"),
                    act(MIRA, CharacterActivity.TALKING, "impatient and frightened for her"),
                    remember(
                        NOVA,
                        "The chip came out of Glass Tower in a dead engineer's coat. I have not " +
                            "read it and I am not reading it in front of a camera.",
                        importance = 5,
                        about = listOf(MIRA),
                        at = MARKET,
                    ),
                    openScene(
                        MARKET,
                        listOf(NOVA, MIRA),
                        "the chip is out, in the open, and being discussed by everyone",
                        listOf(T_CHIP),
                    ),
                ),
                participants = listOf(NOVA, MIRA),
                location = MARKET,
                threads = listOf(
                    threadRef(T_CHIP, stage = 1, status = StoryThreadStatus.ACTIVE, note = "the chip is in play"),
                ),
                tags = listOf("opening", "mystery", "inciting-incident"),
                seedArt = "neon-event-chip",
            ),
            // 2. Scenario-specific opening beat for the clinic scenario.
            PackAuthoring.event(
                id = E_INTAKE,
                title = "Night intake at Mnemonic",
                trigger = EventTrigger.StoryStart(listOf("clinic-appointment")),
                description = "The intake desk asks Mira to sign for a memory she never lost. Nobody at " +
                    "the desk finds this strange, which is the strange part.",
                seed = "A bright, quiet, aggressively welcoming room at four in the morning. A " +
                    "clipboard with one line already filled in, and a signature line waiting for a " +
                    "person who was not expecting to be asked.",
                conditions = listOf(whenAt(MIRA, CLINIC)),
                effects = listOf(
                    grant(MIRA, F_CLIENT, via = "a clipboard nobody cleared before she reached it"),
                    grant(ARCHIVIST, F_TOWER, via = "intake paperwork filed under the wrong heading"),
                    setVar("clinic_intake_log", "one"),
                    act(MIRA, CharacterActivity.WORKING, "focused and not listening"),
                    act(ARCHIVIST, CharacterActivity.IDLE, "amplified from somewhere below"),
                    tick(5),
                    remember(
                        MIRA,
                        "The clinic asked me to sign for a memory I did not lose. I signed. I want " +
                            "that on the record in somebody else's handwriting.",
                        importance = 4,
                        at = CLINIC,
                        about = listOf(ARCHIVIST),
                    ),
                ),
                participants = listOf(MIRA, ARCHIVIST),
                location = CLINIC,
                threads = listOf(threadRef(T_ARCHIVIST, stage = 1, note = "the intake paperwork has a wrong heading")),
                tags = listOf("opening", "intrigue", "paperwork"),
                seedArt = "neon-event-intake",
            ),
            // 3. Conditional: the beat the whole first night is built around.
            PackAuthoring.event(
                id = E_BLACKOUT,
                title = "Blackout",
                trigger = EventTrigger.WhenConditionMet(15),
                description = "Three in the morning and the entire district goes out at once. That is " +
                    "not how a grid fails.",
                seed = "Every sign in Afterlight dies mid-word. The rain does not change, which is " +
                    "how everybody knows the power went rather than the weather. For ninety seconds " +
                    "the district is only rain and the red bands on the stall awnings.",
                conditions = listOf(
                    whenThreadAtLeast(T_POWER, 1),
                    whenVarNot("power_state", "blackout"),
                    whenClock(StoryTime(day = 1, hour = 3, minute = 0)),
                ),
                effects = listOf(
                    setVar("power_state", "blackout"),
                    setVar("market_neon", "off"),
                    act(NOVA, CharacterActivity.FLEEING, "blind in the sudden dark"),
                    act(MIRA, CharacterActivity.WORKING, "calm under sirens"),
                    act(KADE, CharacterActivity.INVESTIGATING, "already awake, already walking"),
                    advance(
                        T_BLACKOUT,
                        stage = 2,
                        status = StoryThreadStatus.ACTIVE,
                        note = "the district bus was pulled by hand, not lost",
                    ),
                    remember(
                        NOVA,
                        "The whole district went out at once. A grid does not fail like that. Somebody " +
                            "asked it to stop.",
                        importance = 5,
                        about = listOf(KADE),
                        at = MARKET,
                    ),
                    openScene(OLD, listOf(NOVA, MIRA, KADE), "the district is dark and the dark is a decision", listOf(T_BLACKOUT)),
                    // Chaining: a conditional event scheduled from an authored one becomes a
                    // guaranteed beat. This is how a pack escalates without a branching tree.
                    later(E_LOCKDOWN, 25),
                ),
                participants = listOf(NOVA, MIRA, KADE),
                location = MARKET,
                cooldownMinutes = 360,
                repeatable = true,
                threads = listOf(threadRef(T_BLACKOUT, note = "the pull was deliberate")),
                tags = listOf("crisis", "action", "set-piece"),
                seedArt = "neon-event-blackout",
            ),
            // 4. Conditional: the corporation answers the outage with paperwork.
            PackAuthoring.event(
                id = E_LOCKDOWN,
                title = "Corporate lockdown",
                trigger = EventTrigger.WhenConditionMet(20),
                description = "Helix declares a controlled outage, seals the atrium, and turns the " +
                    "district's own emergency power into a search operation.",
                seed = "Announcements with the wrong tempo, barriers that arrive faster than the " +
                    "police, and a man in a corridor politely asking everyone to have their " +
                    "continuity documentation to hand.",
                conditions = listOf(
                    whenVar("power_state", "blackout"),
                    whenThreadAtLeast(T_BLACKOUT, 2),
                    whenAt(KADE, METRO),
                ),
                effects = listOf(
                    setVar("helix_lockdown", "true"),
                    setVar("power_state", "rerouted"),
                    move(KADE, OLD, CharacterActivity.INVESTIGATING),
                    act(ELIAS, CharacterActivity.INVESTIGATING, "unhurried"),
                    act(MIRA, CharacterActivity.FLEEING, "with a bag"),
                    relate(ELIAS, NOVA, trust = -6, affinity = -8, reason = "the courier is now an incident"),
                    relate(KADE, NOVA, trust = -4, affinity = -5, reason = "he walked past her in the atrium"),
                    advance(T_LOCKDOWN, stage = 2, note = "the atrium is sealed and the district is being searched"),
                    remember(
                        KADE,
                        "They sealed the atrium during an outage. That is not an outage procedure. " +
                            "That is a search with a power cut for cover.",
                        importance = 5,
                        about = listOf(ELIAS),
                        at = METRO,
                    ),
                    later(E_RESTORE, 60),
                ),
                participants = listOf(ELIAS, KADE, NOVA),
                location = METRO,
                cooldownMinutes = 420,
                repeatable = false,
                threads = listOf(threadRef(T_LOCKDOWN, stage = 2, note = "the blackout was cover for a search")),
                tags = listOf("corporate", "escalation", "set-piece"),
                seedArt = "neon-event-lockdown",
            ),
            // 5. Scheduled: the meeting the market has been promising since the chip surfaced.
            PackAuthoring.event(
                id = E_UNDERGROUND,
                title = "The underground meeting",
                trigger = EventTrigger.AfterDelay(75),
                description = "Platform Nine, in the rain, under working amplifiers. Three people who " +
                    "have each been describing the same eleven minutes differently.",
                seed = "A vault with water coming through the far end and a mask on a hook above a " +
                    "chair. Nobody raises their voice because the room is honest in a way the street " +
                    "above it never is.",
                conditions = listOf(whenThreadAtLeast(T_MARKET, 1)),
                effects = listOf(
                    openScene(
                        UNDER,
                        listOf(NOVA, ARCHIVIST, KADE),
                        "three people and one envelope of silence",
                        listOf(T_MARKET),
                    ),
                    grant(NOVA, F_ARCHIVIST, via = "the relay went quiet and then it did not come back the same"),
                    act(NOVA, CharacterActivity.TALKING, "very still"),
                    act(KADE, CharacterActivity.IDLE, "out of his remit"),
                    act(ARCHIVIST, CharacterActivity.TALKING, "trading"),
                    relate(NOVA, ARCHIVIST, familiarity = 6, trust = 3, reason = "she paid, and paid honestly"),
                    tick(10),
                    remember(
                        NOVA,
                        "The Archivist said something tonight and then the relay mask came off the " +
                            "hook on its own. That is not a person. That is a machine with a person " +
                            "in it somewhere.",
                        importance = 5,
                        about = listOf(ARCHIVIST),
                        at = UNDER,
                    ),
                ),
                participants = listOf(NOVA, KADE, ARCHIVIST),
                location = UNDER,
                cooldownMinutes = 240,
                repeatable = false,
                threads = listOf(
                    threadRef(T_MARKET, stage = 2, note = "the meeting happened"),
                    threadRef(T_ARCHIVIST, stage = 2, note = "the mask came off the hook"),
                ),
                tags = listOf("meeting", "conspiracy", "payoff"),
                seedArt = "neon-event-underground",
            ),
            // 6. Scheduled: the district comes back, and nothing that happened is undone by it.
            PackAuthoring.event(
                id = E_RESTORE,
                title = "District power restoration",
                trigger = EventTrigger.AfterDelay(210),
                description = "The bus comes back on a schedule nobody chose, and the announcement is " +
                    "worded as though nothing happened at all.",
                seed = "Every sign in the district coming back mid-sentence, the red bands going " +
                    "out, and six hundred people who were not outside suddenly being outside again " +
                    "because the shutters have power now.",
                conditions = listOf(whenVar("helix_lockdown", "true")),
                effects = listOf(
                    setVar("power_state", "restored"),
                    setVar("helix_lockdown", "false"),
                    setVar("market_neon", "full"),
                    tick(15),
                    advance(T_POWER, stage = 3, status = StoryThreadStatus.ACTIVE, note = "the bus returned on Helix's schedule"),
                    relate(ELIAS, NOVA, trust = -4, affinity = -6, reason = "the reroute was never hers to sign"),
                    relate(MIRA, NOVA, familiarity = 4, affinity = 3, reason = "they were awake together through it"),
                    closeScene("scene-underground-handover", "the district lit up and the argument continued in daylight"),
                    remember(
                        NOVA,
                        "The lights came back at 06:12 and the announcement thanked the grid " +
                            "engineers. Eleven minutes of my life got a better script than mine did.",
                        importance = 3,
                        about = listOf(MIRA),
                        at = METRO,
                    ),
                ),
                participants = listOf(NOVA, MIRA, ELIAS),
                location = METRO,
                cooldownMinutes = 360,
                repeatable = false,
                threads = listOf(threadRef(T_POWER, stage = 3, note = "power restored, accountability deferred")),
                tags = listOf("payoff", "quiet", "corporate"),
                seedArt = "neon-event-restoration",
            ),
            // 7. Conditional: the only way to the bus authority runs through a service stairwell.
            PackAuthoring.event(
                id = E_ASCENT,
                title = "Ascent to the maintenance spine",
                trigger = EventTrigger.WhenConditionMet(30),
                description = "Floors 33 to 40 are printed on the directory and unlit on the lift panel. " +
                    "There is a stairwell, and somebody is standing in it.",
                seed = "Thirty-two flights of service stairs with the corporate lighting running on " +
                    "reserve power, and two people who have arrived at the same landing from opposite " +
                    "directions with no plausible way to have met there by accident.",
                conditions = listOf(
                    whenAt(KADE, GLASS),
                    whenAt(NOVA, METRO),
                    whenThreadAtLeast(T_LOCKDOWN, 2),
                    whenKnows(KADE, F_TOWER),
                ),
                effects = listOf(
                    move(NOVA, GLASS, CharacterActivity.INVESTIGATING),
                    move(KADE, METRO, CharacterActivity.INVESTIGATING),
                    grant(NOVA, F_TOWER, via = "the spine panel she cracked open at floor 33"),
                    grant(NOVA, F_REROUTE, via = "a signed override on a bus authority panel"),
                    relate(KADE, NOVA, trust = -5, affinity = -4, reason = "they met on a landing neither of them cleared"),
                    remember(
                        NOVA,
                        "The reroute has a human signature on it. Vane says systems don't sign. " +
                            "I have it on a panel in my handwriting notes.",
                        importance = 5,
                        about = listOf(KADE, ELIAS),
                        at = GLASS,
                    ),
                    remember(
                        KADE,
                        "She got to floor 33 before the spine log did. I am going to have to explain " +
                            "that to somebody in a suit.",
                        importance = 4,
                        about = listOf(NOVA),
                        at = GLASS,
                    ),
                ),
                participants = listOf(NOVA, KADE),
                location = GLASS,
                cooldownMinutes = 300,
                repeatable = true,
                threads = listOf(threadRef(T_LOCKDOWN, stage = 3, note = "the spine log has a signed override")),
                tags = listOf("investigation", "physical", "clue"),
                seedArt = "neon-event-ascent",
            ),
            // 8. Conditional: the debt, and the accusation it produces.
            PackAuthoring.event(
                id = E_LEDGER,
                title = "The courier's ledger",
                trigger = EventTrigger.WhenConditionMet(45),
                description = "Someone in the market settles Nova's debt in full, and Mira's slate " +
                    "gets scrubbed at the same minute.",
                seed = "A debt that has been unpaid for eleven months cleared overnight with no name " +
                    "attached, and a signal woman standing in the rain realising the client list is " +
                    "gone from her own relay and she cannot prove it wasn't her.",
                conditions = listOf(
                    whenThreadAtLeast(T_DEBT, 1),
                    whenAt(MIRA, UNDER),
                    whenAt(NOVA, UNDER),
                ),
                effects = listOf(
                    forget(MIRA, F_CLIENT, reason = "the list was scrubbed from her slate at the gate"),
                    setVar("market_debts_open", "3"),
                    relate(MIRA, NOVA, trust = -9, affinity = -4, reason = "she believes Nova sold the client list"),
                    relate(NOVA, KADE, trust = -6, affinity = -8, reason = "the settlement had a corporate signature"),
                    act(NOVA, CharacterActivity.FLEEING, "cornered and holding it together"),
                    act(MIRA, CharacterActivity.INVESTIGATING, "cold, and working"),
                    advance(T_DEBT, stage = 2, note = "the ledger came due and somebody paid it"),
                    openScene(
                        UNDER,
                        listOf(NOVA, MIRA),
                        "an accusation that is not quite a lie",
                        listOf(T_DEBT),
                    ),
                    remember(
                        NOVA,
                        "Mira thinks I sold her. I can live with that for about a week, because " +
                            "somebody just paid eleven months of my debt in cash.",
                        importance = 5,
                        about = listOf(MIRA),
                        at = UNDER,
                    ),
                ),
                participants = listOf(NOVA, MIRA),
                location = UNDER,
                cooldownMinutes = 420,
                repeatable = true,
                threads = listOf(threadRef(T_DEBT, stage = 2, note = "the debt was settled by a third party")),
                tags = listOf("betrayal", "relationship", "sting"),
                seedArt = "neon-event-ledger",
            ),
            // 9. Conditional: the quiet scene, because a pack with no quiet scene has no stakes later.
            PackAuthoring.event(
                id = E_RELAY,
                title = "Rain on the relay glass",
                trigger = EventTrigger.WhenConditionMet(60),
                description = "Nothing happens on Platform Nine for eleven minutes. It is the most " +
                    "important thing that occurs all night.",
                seed = "Water coming through the vault at a rate you can time, a relay holding steady, " +
                    "and two people having a conversation that costs nothing and gives something away " +
                    "anyway.",
                conditions = listOf(
                    whenAt(ARCHIVIST, UNDER),
                    whenThreadAtLeast(T_ARCHIVIST, 1),
                    whenKnows(NOVA, F_ARCHIVIST),
                    // The scene only exists once she trusts the voice more than the mask.
                    whenTrust(NOVA, ARCHIVIST, 40),
                ),
                effects = listOf(
                    act(ARCHIVIST, CharacterActivity.RESTING, "tired in a way that leaks through the relay"),
                    act(NOVA, CharacterActivity.RESTING, "listening"),
                    relate(
                        ARCHIVIST,
                        NOVA,
                        familiarity = 5,
                        trust = 4,
                        reason = "a conversation that cost nothing and gave something",
                    ),
                    relate(NOVA, MIRA, familiarity = 3, reason = "she finally said out loud what she suspected"),
                    tick(20),
                    remember(
                        ARCHIVIST,
                        "Someone in this district still remembers the rain. I let them. That is the " +
                            "last thing I am going to allow myself to be generous about.",
                        importance = 4,
                        about = listOf(NOVA),
                        at = UNDER,
                    ),
                    remember(
                        NOVA,
                        "The Archivist's voice was almost a voice tonight. Almost. I want to keep " +
                            "hearing that and I know exactly how much it costs.",
                        importance = 3,
                        about = listOf(ARCHIVIST),
                        at = UNDER,
                    ),
                ),
                participants = listOf(NOVA, ARCHIVIST),
                location = UNDER,
                cooldownMinutes = 480,
                repeatable = true,
                threads = listOf(threadRef(T_ARCHIVIST, note = "the relay conversation")),
                tags = listOf("quiet", "relationship", "breather"),
                seedArt = "neon-event-relay",
            ),
        ),
        // ---------------------------------------------------------------
        scenarios = listOf(
            PackAuthoring.scenario(
                id = "first-night-in-afterlight",
                title = "First Night in Afterlight",
                tagline = "Eleven minutes before midnight, and something is already moving",
                description = "You arrive in the district with a courier's coat and no address. Nova " +
                    "is two stalls down with a case she will not open, Mira is closing a clinic that " +
                    "never closes, and the substation under Glass Tower has just been signed off for " +
                    "maintenance that nobody scheduled.",
                startTime = StoryTime(day = 1, hour = 23, minute = 40),
                startLocation = MARKET,
                focus = NOVA,
                cast = listOf(NOVA, MIRA),
                activities = mapOf(
                    NOVA to CharacterActivity.WORKING,
                    MIRA to CharacterActivity.WORKING,
                    KADE to CharacterActivity.WORKING,
                ),
                threadStages = mapOf(
                    T_CHIP to 1,
                    T_POWER to 1,
                    T_BLACKOUT to 1,
                    T_LOCKDOWN to 0,
                    T_MARKET to 1,
                    T_ARCHIVIST to 0,
                    T_DEBT to 1,
                ),
                variables = listOf(
                    flag("first_night", "true", "The player arrived without an address (market)"),
                ),
                // The universal StoryStart event does the work here, so the scenario
                // does not need to name any event id explicitly.
                events = emptyList(),
                seed = "Rain from 22:00 and no sign of stopping. The market is loud enough that " +
                    "nobody has to whisper and the district is one hour from doing something " +
                    "deliberate.",
                artSeed = "neon-scenario-first-night",
            ),
            PackAuthoring.scenario(
                id = "clinic-appointment",
                title = "The Clinic Appointment",
                tagline = "A clean, bright room and one line already filled in",
                description = "Four in the morning at Mnemonic Health. Mira is here for the free intake " +
                    "she has skipped four times, the waiting room is empty in the way a waiting room " +
                    "is empty when it is a set, and somewhere below the building a relay is holding " +
                    "a conversation open.",
                startTime = StoryTime(day = 1, hour = 4, minute = 10),
                startLocation = CLINIC,
                focus = MIRA,
                cast = listOf(MIRA, ARCHIVIST, NOVA),
                activities = mapOf(
                    MIRA to CharacterActivity.WORKING,
                    ARCHIVIST to CharacterActivity.IDLE,
                    NOVA to CharacterActivity.TRAVELLING,
                ),
                threadStages = mapOf(
                    T_ARCHIVIST to 1,
                    T_CHIP to 1,
                    T_POWER to 2,
                    T_BLACKOUT to 2,
                    T_LOCKDOWN to 1,
                    T_MARKET to 0,
                    T_DEBT to 1,
                ),
                variables = listOf(
                    flag("intake_pending", "true", "A signature is still wanted at the clinic desk"),
                ),
                events = emptyList(),
                seed = "Booths 4 and 5 are maintenance and are never occupied, which is worth " +
                    "thinking about at four in the morning.",
                artSeed = "neon-scenario-clinic",
            ),
            PackAuthoring.scenario(
                id = "blackout-at-0300",
                title = "Blackout at 03:00",
                tagline = "Two minutes before the district goes out, and you are standing under it",
                description = "The Old District, in the warm dark of a block that was evacuated and " +
                    "reoccupied. Kade is off shift and illegally present. Nova is running a delivery " +
                    "she should not have accepted. At 03:00 the substation bus is going to be pulled " +
                    "by somebody with a signature.",
                startTime = StoryTime(day = 2, hour = 2, minute = 58),
                startLocation = OLD,
                focus = KADE,
                cast = listOf(KADE, NOVA, MIRA),
                activities = mapOf(
                    KADE to CharacterActivity.INVESTIGATING,
                    NOVA to CharacterActivity.TRAVELLING,
                    MIRA to CharacterActivity.RESTING,
                ),
                threadStages = mapOf(
                    T_BLACKOUT to 1,
                    T_POWER to 2,
                    T_LOCKDOWN to 1,
                    T_CHIP to 1,
                    T_MARKET to 0,
                    T_ARCHIVIST to 1,
                    T_DEBT to 1,
                ),
                variables = listOf(
                    count("minutes_of_night", 0, "How long the player has been out before the pull (old district)"),
                ),
                events = emptyList(),
                seed = "Two minutes. Somebody on the substation level is already holding the override " +
                    "switch and has not decided whether to use it.",
                artSeed = "neon-scenario-blackout",
            ),
        ),
        // ---------------------------------------------------------------
        personas = listOf(
            PackAuthoring.persona(
                id = "new-arrival",
                name = "New arrival",
                tagline = "Nobody knows your name and everybody assumes you can pay",
                description = "You got into Afterlight on a night bus with a bag and a contact who " +
                    "has not called back. You have no history here, which makes you the safest " +
                    "person in the market and the easiest to trust.",
                rolePrompt = "You are new to Afterlight. You do not know the district's rules, its " +
                    "factions or its hours, and you will be treated as either a customer or an " +
                    "opportunity until somebody vouches for you. You have no secret of your own, " +
                    "which is rarer here than it sounds.",
                location = METRO,
                suggests = listOf(NOVA, MIRA, ARCHIVIST),
            ),
            PackAuthoring.persona(
                id = "corporate-defector",
                name = "Corporate defector",
                tagline = "You have the clearance codes and nothing to lose by using them",
                description = "You walked out of Helix Biodyne with a continuity badge that still " +
                    "opens doors it was never supposed to keep opening, and a professional habit " +
                    "of reading a room's clearance levels before its occupants.",
                rolePrompt = "You are a former corporate continuity officer. You know how Helix " +
                    "builds a case, what a consent form is for internally, and exactly which " +
                    "questions get a person transferred rather than fired. You are not an ally of " +
                    "anyone here yet and you are aware that everyone assumes otherwise.",
                location = GLASS,
                suggests = listOf(ELIAS, KADE, ARCHIVIST),
            ),
            PackAuthoring.persona(
                id = "street-medic",
                name = "Street medic",
                tagline = "You have seen what four hundred sleepless nights look like",
                description = "You have run free intake out of a market stall and a corridor for " +
                    "long enough that the district knows your face before it knows your name. You " +
                    "are trusted in the places that do not file reports.",
                rolePrompt = "You are a street medic working Afterlight's illegal hours. You " +
                    "treat everyone and report nobody, you know which corridor kitchens will feed " +
                    "you, and you have already decided that the clinic's paperwork is not worth " +
                    "trusting even though the clinic has your file.",
                location = CLINIC,
                suggests = listOf(MIRA, NOVA, ARCHIVIST),
            ),
        ),
        // ---------------------------------------------------------------
        startTime = StoryTime(day = 1, hour = 23, minute = 40),
        variables = listOf(
            flag("power_state", "nominal", "District bus: nominal / rerouted / blackout / restored"),
            flag("helix_lockdown", "false", "Glass Tower and the metro approach are sealed (tower)"),
            flag("chip_located", "false", "Somebody in the district knows where the chip is (market)"),
            flag("market_neon", "full", "Stall lighting state: full / off"),
            text("district_mood", "wary", "How the district is behaving tonight (market)"),
            text("archivist_channel", "closed", "Whether the Platform Nine relay is accepting calls (under)"),
            count("blackouts", 0, "Blackouts since the story began (district)"),
            count("market_debts_open", 2, "Open favours owed in the market (market)"),
        ),
        startLocations = mapOf(
            NOVA to MARKET,
            MIRA to CLINIC,
            KADE to GLASS,
            ELIAS to GLASS,
            ARCHIVIST to UNDER,
        ),
        startActivities = mapOf(
            NOVA to CharacterActivity.WORKING,
            MIRA to CharacterActivity.WORKING,
            KADE to CharacterActivity.WORKING,
            ELIAS to CharacterActivity.IDLE,
            ARCHIVIST to CharacterActivity.IDLE,
        ),
        startGoals = mapOf(
            NOVA to listOf(
                "Clear the market debt",
                "Read the chip somewhere nobody is watching",
            ),
            MIRA to listOf(
                "Finish the night intake",
                "Keep Nova out of the tower",
            ),
            KADE to listOf(
                "Close the incident without an incident report",
                "Find the courier before the board asks",
            ),
            ELIAS to listOf(
                "Recover the chip before it is read outside the tower",
                "Keep the outage off the quarterly report",
            ),
            ARCHIVIST to listOf(
                "Make sure the client list reaches somebody who will use it",
                "Sell one last memory and stop",
            ),
        ),
        // ---------------------------------------------------------------
        relationships = listOf(
            PackAuthoring.relationship(NOVA, MIRA, RelationshipType.CLOSE_FRIEND, trust = 78, familiarity = 86, affinity = 74, note = "eleven years of shared corridors"),
            PackAuthoring.relationship(MIRA, NOVA, RelationshipType.CLOSE_FRIEND, trust = 81, familiarity = 86, affinity = 78, note = "She would do anything and refuses to say so"),
            PackAuthoring.relationship(NOVA, KADE, RelationshipType.RIVAL, trust = 22, familiarity = 66, affinity = 16, note = "he has her badge number on a list"),
            PackAuthoring.relationship(KADE, NOVA, RelationshipType.RIVAL, trust = 18, familiarity = 66, affinity = 12, note = "an open file, technically"),
            PackAuthoring.relationship(NOVA, ELIAS, RelationshipType.ENEMY, trust = 8, familiarity = 30, affinity = 2, note = "she has never met him and would still not like him"),
            PackAuthoring.relationship(ELIAS, NOVA, RelationshipType.UNKNOWN, trust = 10, familiarity = 12, affinity = 0, note = "a courier on a report"),
            PackAuthoring.relationship(MIRA, KADE, RelationshipType.ACQUAINTANCE, trust = 35, familiarity = 44, affinity = 20, note = "he has never once asked for help"),
            PackAuthoring.relationship(KADE, MIRA, RelationshipType.ACQUAINTANCE, trust = 30, familiarity = 44, affinity = 24, note = "he respects the clinic's records and says so once a year"),
            PackAuthoring.relationship(ELIAS, KADE, RelationshipType.ALLY, trust = 70, familiarity = 80, affinity = 52, note = "contract staff, and everyone knows what that means"),
            PackAuthoring.relationship(KADE, ELIAS, RelationshipType.ALLY, trust = 62, familiarity = 80, affinity = 40, note = "on the payroll, not on the list of people who get told"),
            PackAuthoring.relationship(ARCHIVIST, MIRA, RelationshipType.MENTOR, trust = 62, familiarity = 70, affinity = 46, note = "Taught her to read a relay before she could drive"),
            PackAuthoring.relationship(MIRA, ARCHIVIST, RelationshipType.STUDENT, trust = 58, familiarity = 70, affinity = 52, note = "still half believes they are an old man behind a mask"),
            PackAuthoring.relationship(ARCHIVIST, ELIAS, RelationshipType.ALLY, trust = 55, familiarity = 68, affinity = 20, note = "a standing arrangement neither of them enjoys"),
            PackAuthoring.relationship(NOVA, ARCHIVIST, RelationshipType.ACQUAINTANCE, trust = 44, familiarity = 52, affinity = 30, note = "four trades, one argument"),
        ),
        // ---------------------------------------------------------------
        facts = listOf(
            PackAuthoring.fact(
                id = F_CHIP,
                subject = "the missing memory chip",
                predicate = "holds",
                description = "A Mnemonic-pattern memory core taken out of Glass Tower inside a dead " +
                    "engineer's coat. It has not been read. Everybody in the district believes it is " +
                    "worth a district.",
                location = MARKET,
                involves = listOf(NOVA, MIRA, KADE),
                secret = true,
            ),
            PackAuthoring.fact(
                id = F_CLIENT,
                subject = "the client list",
                predicate = "is stored inside",
                description = "The chip carries a full client index for unlicensed engram trades: " +
                    "names, dates, impressions sold, including several district officials.",
                location = UNDER,
                involves = listOf(MIRA, ARCHIVIST),
                secret = true,
            ),
            PackAuthoring.fact(
                id = F_ARCHIVIST,
                subject = "the Archivist",
                predicate = "is",
                description = "The Archivist is a relay rig worn by a person under Mnemonic Health " +
                    "maintenance since the Platform Nine closure. They have no body of their own, and " +
                    "the mask on the hook is only ever half the truth.",
                location = UNDER,
                involves = listOf(ARCHIVIST, MIRA),
                secret = true,
            ),
            PackAuthoring.fact(
                id = F_TOWER,
                subject = "Glass Tower",
                predicate = "holds the authority over",
                description = "Glass Tower substation 33 holds the district bus authority for " +
                    "Afterlight, including the unlit floors 33 to 40 and the Old District supply that " +
                    "was never actually disconnected.",
                location = GLASS,
                involves = listOf(ELIAS, KADE),
            ),
            PackAuthoring.fact(
                id = F_DEBT,
                subject = "Nova's debt",
                predicate = "is owed to",
                description = "Nova owes eleven months of market settlement to an unnamed collector. " +
                    "The settlement is late, and the market treats a late settlement as an invitation.",
                location = MARKET,
                involves = listOf(NOVA, MIRA),
                secret = true,
            ),
            PackAuthoring.fact(
                id = F_ENG,
                subject = "the engineer",
                predicate = "died inside",
                description = "The engineer whose coat the chip came out of died on floor 36 of Glass " +
                    "Tower during the blackout, officially of a transit accident. Her last eleven " +
                    "minutes are the ones on the chip.",
                location = GLASS,
                involves = listOf(ELIAS, KADE),
                secret = true,
            ),
            PackAuthoring.fact(
                id = F_REROUTE,
                subject = "the blackout",
                predicate = "was caused by",
                description = "The blackout was a signed manual override on substation 33, not a grid " +
                    "failure. There is a human signature on the panel and it is not a system account.",
                location = GLASS,
                involves = listOf(ELIAS, KADE, NOVA),
                secret = true,
            ),
        ),
        // ---------------------------------------------------------------
        // The separation that makes the pack worth playing: each of these grants is
        // the *only* reason that character can act on the fact.
        characterKnowledge = listOf(
            PackAuthoring.knows(NOVA, F_CHIP, F_DEBT),
            PackAuthoring.knows(MIRA, F_CHIP, F_DEBT),
            PackAuthoring.knows(KADE, F_TOWER),
            PackAuthoring.knows(ELIAS, F_TOWER, F_ENG),
            PackAuthoring.knows(ARCHIVIST, F_TOWER, F_DEBT),
        ),
        // ---------------------------------------------------------------
        threads = listOf(
            PackAuthoring.thread(
                id = T_BLACKOUT,
                title = "Blackout at 03:00",
                description = "The district goes dark on a schedule that has nothing to do with the " +
                    "grid. Every outage begins as an outage and ends as a signature.",
                status = StoryThreadStatus.ACTIVE,
                stage = 1,
                characters = listOf(NOVA, MIRA, KADE),
                locations = listOf(MARKET, OLD, GLASS),
            ),
            PackAuthoring.thread(
                id = T_CHIP,
                title = "The missing memory chip",
                description = "A dead engineer's eleven minutes are sitting in a courier's satchel. " +
                    "Reading it will make somebody's crime provable, which is the problem.",
                status = StoryThreadStatus.ACTIVE,
                stage = 1,
                characters = listOf(NOVA, MIRA, KADE, ELIAS),
                locations = listOf(MARKET, GLASS),
            ),
            PackAuthoring.thread(
                id = T_LOCKDOWN,
                title = "Corporate lockdown",
                description = "Helix turns an outage into a search, the atrium into a checkpoint, and " +
                    "a maintenance log into evidence that somebody is deleting a night.",
                status = StoryThreadStatus.DORMANT,
                stage = 0,
                characters = listOf(ELIAS, KADE),
                locations = listOf(GLASS, METRO),
            ),
            PackAuthoring.thread(
                id = T_MARKET,
                title = "The underground meeting",
                description = "Somebody on Platform Nine has been buying up everyone's half-knowledge " +
                    "for eleven months, and the meeting where the halves meet is booked.",
                status = StoryThreadStatus.ACTIVE,
                stage = 1,
                characters = listOf(NOVA, ARCHIVIST, KADE),
                locations = listOf(UNDER, MARKET),
            ),
            PackAuthoring.thread(
                id = T_POWER,
                title = "District power restoration",
                description = "The bus comes back every morning at 06:12. The district has learned " +
                    "to read the outage notices as a schedule, which means somebody wrote them.",
                status = StoryThreadStatus.ACTIVE,
                stage = 2,
                characters = listOf(NOVA, MIRA, ELIAS),
                locations = listOf(METRO, OLD, GLASS),
            ),
            PackAuthoring.thread(
                id = T_ARCHIVIST,
                title = "Who is really on Platform Nine",
                description = "The mask, the relay, the maintenance contract. Mira has been talking to " +
                    "a person she imagines, and the person she imagines is wrong.",
                status = StoryThreadStatus.DORMANT,
                stage = 0,
                characters = listOf(ARCHIVIST, MIRA, ELIAS),
                locations = listOf(UNDER, CLINIC),
            ),
            PackAuthoring.thread(
                id = T_DEBT,
                title = "Nova's settlement",
                description = "Eleven months of market settlement, due at the third bell. If it is " +
                    "paid for her, somebody wanted her quiet, not her solvent.",
                status = StoryThreadStatus.ACTIVE,
                stage = 1,
                characters = listOf(NOVA, MIRA, KADE),
                locations = listOf(MARKET, UNDER),
            ),
        ),
        // ---------------------------------------------------------------
        authoredMemories = listOf(
            PackAuthoring.memory(
                id = "mem-nova-satchel",
                character = NOVA,
                content = "The satchel weighs almost nothing, which is the worst thing about it. " +
                    "Whatever is in there is small.",
                importance = 5,
                at = MARKET,
                involves = listOf(MIRA),
            ),
            PackAuthoring.memory(
                id = "mem-nova-last-contract",
                character = NOVA,
                content = "The Helix contract froze on a Thursday. Nobody explained why and nobody " +
                    "returned the badge, so I burned it and kept the rig.",
                importance = 4,
                at = MARKET,
                involves = listOf(ELIAS),
            ),
            PackAuthoring.memory(
                id = "mem-mira-intake",
                character = MIRA,
                content = "Booths 4 and 5 are maintenance and have never once been occupied. I have " +
                    "checked. I have checked more than once.",
                importance = 4,
                at = CLINIC,
                involves = listOf(ARCHIVIST),
            ),
            PackAuthoring.memory(
                id = "mem-mira-grandfather",
                character = MIRA,
                content = "Granddad's Platform Nine keycard still opens the service door. He told me " +
                    "it was useless and then he gave it to me anyway, which is a whole speech in " +
                    "one gesture.",
                importance = 4,
                at = UNDER,
                involves = listOf(ARCHIVIST),
            ),
            PackAuthoring.memory(
                id = "mem-kade-arcology",
                character = KADE,
                content = "Corridor four of the Old District block used to be my grandmother's. It is " +
                    "a tomato balcony now and I have never been able to walk past it faster than " +
                    "walking pace.",
                importance = 4,
                at = OLD,
                involves = listOf(MIRA),
            ),
            PackAuthoring.memory(
                id = "mem-elias-signature",
                character = ELIAS,
                content = "The override was authorised by a human account. I authorised it. The " +
                    "difference between those two sentences is the entire career of the man I used " +
                    "to be.",
                importance = 5,
                at = GLASS,
                involves = listOf(KADE),
            ),
            PackAuthoring.memory(
                id = "mem-archivist-last-night",
                character = ARCHIVIST,
                content = "The amplifier in the vault cut out for nine seconds tonight and I heard " +
                    "the rain instead. I have not heard rain since the closure.",
                importance = 5,
                at = UNDER,
                involves = listOf(NOVA),
            ),
        ),
        // Pack-level art. Original generated illustrations: the cover and banner a
        // library card needs, plus one image per recurring incident. No official
        // artwork and no network fetch, so the pack renders identically offline.
        visualAssets = listOf(
            PackAuthoring.packCover("neon-district-cover", "neon-district-afterlight"),
            PackAuthoring.packBanner(
                "neon-district-banner",
                "neon-district-afterlight-banner",
                "Afterlight, and the invoice that comes with it",
            ),
            PackAuthoring.eventImage("neon-district-signal-loss", "neon-district-signal-loss-art", "The signal drops"),
            PackAuthoring.eventImage("neon-district-missing-person", "neon-district-missing-person-art", "Nobody reports the disappeared"),
        ),
        defaultModelProfileId = ModelProfileLibrary.CINEMATIC,
    )
}