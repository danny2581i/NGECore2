package services;

import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import main.NGECore;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;

import protocol.swg.ObjControllerMessage;
import protocol.swg.OkMessage;
import protocol.swg.PlayClientEffectObjectMessage;
import protocol.swg.objectControllerObjects.BuffBuilderChangeMessage;
import protocol.swg.objectControllerObjects.BuffBuilderEndMessage;
import protocol.swg.objectControllerObjects.BuffBuilderStartMessage;
import resources.common.BuffBuilder;
import resources.common.Console;
import resources.common.MathUtilities;
import resources.common.ObjControllerOpcodes;
import resources.common.Performance;
import resources.common.PerformanceEffect;
import resources.common.RGB;
import resources.common.StringUtilities;
import resources.datatables.Posture;
import resources.objects.Buff;
import resources.objects.BuffItem;
import resources.objects.SWGList;
import resources.objects.SkillMod;
import resources.objects.creature.CreatureObject;
import resources.objects.player.PlayerObject;
import resources.objects.tangible.TangibleObject;
import services.sui.SUIService.InputBoxType;
import services.sui.SUIWindow;
import services.sui.SUIWindow.SUICallback;
import services.sui.SUIWindow.Trigger;
import engine.clientdata.ClientFileManager;
import engine.clientdata.visitors.DatatableVisitor;
import engine.clients.Client;
import engine.resources.objects.SWGObject;
import engine.resources.service.INetworkDispatch;
import engine.resources.service.INetworkRemoteEvent;

public class EntertainmentService implements INetworkDispatch {

	private NGECore core;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	
	private Vector<BuffBuilder> buffBuilderSkills = new Vector<BuffBuilder>();
	//FIXME: create a wrapper class for double key lookup maps
	private ConcurrentHashMap<String,Performance> performances = new ConcurrentHashMap<String,Performance>();
	private ConcurrentHashMap<Integer,Performance> performancesByIndex = new ConcurrentHashMap<Integer,Performance>();
	private ConcurrentHashMap<Integer,Performance> danceMap = new ConcurrentHashMap<Integer,Performance>();
	
	private Random ranWorkshop = new Random();
	
	private Map<String, PerformanceEffect> performanceEffects = new ConcurrentHashMap<String, PerformanceEffect>();
	
	public EntertainmentService(NGECore core) {
		this.core = core;
		populateSkillCaps();
		populatePerformanceTable();
		populatePerformanceEffects();
		registerCommands();
	}
	
	@Override
	public void insertOpcodes(Map<Integer, INetworkRemoteEvent> swgOpcodes, Map<Integer, INetworkRemoteEvent> objControllerOpcodes) {
		objControllerOpcodes.put(ObjControllerOpcodes.BUFF_BUILDER_END, new INetworkRemoteEvent() {

			@Override
			public void handlePacket(IoSession session, IoBuffer data) throws Exception {

				data.order(ByteOrder.LITTLE_ENDIAN);

				Client client = core.getClient(session);

				if(client == null)
					return;

				SWGObject sender = client.getParent();

				if(sender == null)
					return;

				BuffBuilderEndMessage sentPacket = new BuffBuilderEndMessage();
				sentPacket.deserialize(data);

				SWGObject buffer = core.objectService.getObject(sentPacket.getBufferId());
				SWGObject buffRecipient = core.objectService.getObject(sentPacket.getBuffRecipientId());

				if(buffer == null || buffRecipient == null)
					return;

				// No need to send end packet when buffing yourself.
				if (buffRecipient != buffer) {
					if(buffRecipient.getClient() == null || buffRecipient.getClient().getSession() == null)
						return;

					if (sender.getObjectId() == buffer.getObjectId()) {
						// send close packet to recipient
						BuffBuilderEndMessage end = new BuffBuilderEndMessage(sentPacket);
						end.setObjectId(buffRecipient.getObjectId());

						end.setBufferId(buffer.getObjectId());
						end.setBuffRecipientId(buffRecipient.getObjectId());

						ObjControllerMessage closeMessage = new ObjControllerMessage(0x0B, end);
						buffRecipient.getClient().getSession().write(closeMessage.serialize());

					}
					if (sender.getObjectId() == buffRecipient.getObjectId()) {
						// send close packet to buffer
						BuffBuilderEndMessage end = new BuffBuilderEndMessage(sentPacket);
						end.setObjectId(buffer.getObjectId());

						end.setBufferId(buffer.getObjectId());
						end.setBuffRecipientId(buffRecipient.getObjectId());

						ObjControllerMessage closeMessage = new ObjControllerMessage(0x0B, end);
						buffer.getClient().getSession().write(closeMessage.serialize());
						CreatureObject cre = (CreatureObject) buffer;
						cre.sendSystemMessage("The buff recipient cancelled the buff builder session.", (byte) 0);
					}
				}
			}
		});

		objControllerOpcodes.put(ObjControllerOpcodes.BUFF_BUILDER_CHANGE, new INetworkRemoteEvent() {

			@Override
			public void handlePacket(IoSession session, IoBuffer data) throws Exception {
				data.order(ByteOrder.LITTLE_ENDIAN);

				Client client = core.getClient(session);

				if(client == null)
					return;

				SWGObject sender = client.getParent();

				if(sender == null)
					return;

				BuffBuilderChangeMessage sentPacket = new BuffBuilderChangeMessage();
				sentPacket.deserialize(data);

				Vector<BuffItem> statBuffs = sentPacket.getStatBuffs();

				CreatureObject buffer = (CreatureObject) core.objectService.getObject(sentPacket.getBufferId());
				CreatureObject buffRecipient = (CreatureObject) core.objectService.getObject(sentPacket.getBuffRecipientId());

				if (buffer != buffRecipient) {

					BuffBuilderChangeMessage changeMessage = new BuffBuilderChangeMessage();
					changeMessage.setBuffCost(sentPacket.getBuffCost());
					changeMessage.setTime(sentPacket.getTime());
					changeMessage.setBufferId(buffer.getObjectId());
					changeMessage.setBuffRecipientId(buffRecipient.getObjectId());

					if (!statBuffs.isEmpty())
						changeMessage.setStatBuffs(statBuffs);

					if (sentPacket.getAccepted() == true && sentPacket.getBuffRecipientAccepted() == 1) {

						if (sentPacket.getBuffCost() > 0) {
							CreatureObject recipientCreature = (CreatureObject) buffRecipient;
							if (recipientCreature.getCashCredits() >= sentPacket.getBuffCost()) {
								recipientCreature.setCashCredits(recipientCreature.getCashCredits() - sentPacket.getBuffCost());
							} else {
								return;
							}
						}

						giveInspirationBuff(buffRecipient, buffer, statBuffs);

						BuffBuilderEndMessage endBuilder = new BuffBuilderEndMessage(changeMessage);
						endBuilder.setObjectId(buffer.getObjectId());

						BuffBuilderEndMessage endRecipient = new BuffBuilderEndMessage(changeMessage);
						endRecipient.setObjectId(buffRecipient.getObjectId());

						ObjControllerMessage closeBuilder = new ObjControllerMessage(0x0B, endBuilder);
						buffer.getClient().getSession().write(closeBuilder.serialize());

						ObjControllerMessage closeRecipient = new ObjControllerMessage(0x0B, endRecipient);
						buffRecipient.getClient().getSession().write(closeRecipient.serialize());

					} else if (sentPacket.getAccepted() == true && sentPacket.getBuffRecipientAccepted() == 0) {
						changeMessage.setAccepted(true);
						changeMessage.setObjectId(buffRecipient.getObjectId());

						ObjControllerMessage objMsg = new ObjControllerMessage(0x0B, changeMessage);
						buffRecipient.getClient().getSession().write(objMsg.serialize());
					} else {
						changeMessage.setAccepted(false);
						changeMessage.setObjectId(sentPacket.getBuffRecipientId());

						ObjControllerMessage objMsg = new ObjControllerMessage(0x0B, changeMessage);
						buffRecipient.getClient().getSession().write(objMsg.serialize());
					}

				} else {
					if (sentPacket.getAccepted() == true) {
						giveInspirationBuff(buffRecipient, buffer, statBuffs);
						BuffBuilderEndMessage endBuilder = new BuffBuilderEndMessage(sentPacket);
						endBuilder.setObjectId(buffer.getObjectId());

						ObjControllerMessage objMsg = new ObjControllerMessage(0x0B, endBuilder);
						buffRecipient.getClient().getSession().write(objMsg.serialize());
					}
				}
			}

		});
	}

	private void populatePerformanceTable() {
		
		try {
			DatatableVisitor PerformanceVisitor = ClientFileManager.loadFile("datatables/performance/performance.iff", DatatableVisitor.class);
			
			//rformanceVisitor.
			
			for (int r = 0; r < PerformanceVisitor.getRowCount(); r++) {
				Performance p = new Performance();
				
				/* for (int j=0; j < PerformanceVisitor.getColumnCount(); j++) {
					System.out.println(j + ": " + PerformanceVisitor.getObject(r, j));
				}*/
				
				p.setPerformanceName( ( (String) PerformanceVisitor.getObject(r, 0) ).toLowerCase());
				p.setInstrumentAudioId((int) PerformanceVisitor.getObject(r, 1));
				p.setRequiredSong((String) PerformanceVisitor.getObject(r, 2));
				p.setRequiredInstrument((String) PerformanceVisitor.getObject(r, 3));
				p.setRequiredDance((String) PerformanceVisitor.getObject(r, 4));
				p.setDanceVisualId((int) PerformanceVisitor.getObject(r, 5));
				p.setActionPointsPerLoop((int) PerformanceVisitor.getObject(r, 6));
				p.setLoopDuration((float) PerformanceVisitor.getObject(r, 7));
				p.setType((int) PerformanceVisitor.getObject(r, 8));
				p.setBaseXp((int) PerformanceVisitor.getObject(r, 9));
				p.setFlourishXpMod((int) PerformanceVisitor.getObject(r, 10));
				p.setHealMindWound((int) PerformanceVisitor.getObject(r, 11));
				p.setHealShockWound((int) PerformanceVisitor.getObject(r, 12));
				p.setRequiredSkillMod((String) PerformanceVisitor.getObject(r, 13));
				p.setRequiredSkillModValue((int) PerformanceVisitor.getObject(r, 14));
				p.setMainloop((String) PerformanceVisitor.getObject(r, 15));
				p.setFlourish1((String) PerformanceVisitor.getObject(r, 16));
				p.setFlourish2((String) PerformanceVisitor.getObject(r, 17));
				p.setFlourish3((String) PerformanceVisitor.getObject(r, 18));
				p.setFlourish4((String) PerformanceVisitor.getObject(r, 19));
				p.setFlourish5((String) PerformanceVisitor.getObject(r, 20));
				p.setFlourish6((String) PerformanceVisitor.getObject(r, 21));
				p.setFlourish7((String) PerformanceVisitor.getObject(r, 22));
				p.setFlourish8((String) PerformanceVisitor.getObject(r, 23));
				p.setIntro((String) PerformanceVisitor.getObject(r, 24));
				p.setOutro((String) PerformanceVisitor.getObject(r, 25));
				p.setLineNumber(r);
				
				if (p.getType() == -1788534963) {
					danceMap.put(new Integer(p.getDanceVisualId()), p);
				}
				performances.put(p.getPerformanceName(), p);
				performancesByIndex.put(r, p);
			}
			
		} catch (InstantiationException | IllegalAccessException e) {
			e.printStackTrace();
		}
	}
	
	private void populateSkillCaps() {
		try {
			DatatableVisitor buffBuilder = ClientFileManager.loadFile("datatables/buff/buff_builder.iff", DatatableVisitor.class);
			
			for (int r = 0; r < buffBuilder.getRowCount(); r++) {
				String skillName = ((String) buffBuilder.getObject(r, 0));
				String category = ((String) buffBuilder.getObject(r, 1));
				String statAffects = ((String) buffBuilder.getObject(r, 2));
				int maxTimes = ((int) buffBuilder.getObject(r, 3));
				int cost = ((int) buffBuilder.getObject(r, 4));
				int affectAmount = ((int) buffBuilder.getObject(r, 5));
				String requiredExpertise = ((String) buffBuilder.getObject(r, 6));
				
				BuffBuilder item = new BuffBuilder();
				item.setStatName(skillName);
				item.setCategory(category);
				item.setStatAffects(statAffects);
				item.setMaxTimesApplied(maxTimes);
				item.setCost(cost);
				item.setAffectAmount(affectAmount);
				item.setRequiredExpertise(requiredExpertise);
				
				buffBuilderSkills.add(item);
			}
		} catch (InstantiationException | IllegalAccessException e) {
			e.printStackTrace();
		}
	}
	
	private void populatePerformanceEffects() {
		try {
			DatatableVisitor effects = ClientFileManager.loadFile("datatables/performance/perform_effect.iff", DatatableVisitor.class);

			for (int r = 0; r < effects.getRowCount(); r++) {
				String effectName = ((String) effects.getObject(r, 0));
				String performanceType = ((String) effects.getObject(r, 1));
				int requiredSkillModValue = ((int) effects.getObject(r, 2));
				Boolean requiredPerforming = ((Boolean) effects.getObject(r, 3));
				int targetType = ((int) effects.getObject(r, 4));
				float effectDuration = ((float) effects.getObject(r, 5));
				int effectActionCost = ((int) effects.getObject(r, 6));

				PerformanceEffect item = new PerformanceEffect();
				item.setEffectActionCost(effectActionCost);
				item.setEffectDuration(effectDuration);
				item.setName(effectName);
				item.setPerformanceType(performanceType);
				item.setRequiredPerforming(requiredPerforming);
				item.setRequiredSkillModValue(requiredSkillModValue);
				item.setTargetType(targetType);

				performanceEffects.put(effectName.toLowerCase(), item);

			}
		} catch (InstantiationException | IllegalAccessException e) {
			e.printStackTrace();
		}
	}
	
	private void registerCommands() {
		core.commandService.registerCommand("bandflourish");
		core.commandService.registerCommand("flourish");
		core.commandService.registerAlias("flo","flourish");
		core.commandService.registerCommand("groupdance");
		core.commandService.registerCommand("startdance");
		core.commandService.registerCommand("stopdance");
		core.commandService.registerCommand("watch");
		//core.commandService.registerCommand("stopwatching"); // SWGList error
		//core.commandService.registerCommand("holoEmote");
		core.commandService.registerCommand("covercharge");
		//core.commandService.registerCommand("en_holographic_recall");
		//core.commandService.registerCommand("en_holographic_image");
		// TODO: Add /bandsolo, /bandpause, /changeBandMusic, /changeDance, /changeGroupDance, /changeMusic
		
		// Entertainer Effects
		core.commandService.registerCommand("centerStage");
		core.commandService.registerCommand("colorSwirl");
		core.commandService.registerCommand("colorlights");
		core.commandService.registerCommand("floorLights"); // referred to also as Dance Floor
		core.commandService.registerCommand("dazzle");
		core.commandService.registerCommand("distract");
		core.commandService.registerCommand("featuredSolo");
		core.commandService.registerCommand("firejet");
		core.commandService.registerCommand("firejet2");
		core.commandService.registerCommand("laserShow");
		core.commandService.registerCommand("smokebomb");
		core.commandService.registerCommand("spotlight");
		core.commandService.registerCommand("ventriloquism");
	}
	
	public void giveInspirationBuff(CreatureObject reciever, CreatureObject buffer, Vector<BuffItem> buffVector) {
		
		Vector<BuffBuilder> availableStats = buffBuilderSkills;
		Vector<BuffItem> stats = new Vector<BuffItem>();

		for (BuffItem item : buffVector) {
			for(BuffBuilder builder : availableStats) {
				if(builder.getStatName().equalsIgnoreCase(item.getSkillName())) {
					if(builder.getMaxTimesApplied() < item.getInvested())
						return;
					
					// Ent. Expertise Percent + (invested points * affect amount) = stat
					int bonusPoints = (int) (builder.getAffectAmount() *((float) item.getEntertainerBonus()/100f));
					int affectTotal = bonusPoints + (item.getInvested() * builder.getAffectAmount());

					BuffItem stat = new BuffItem(builder.getStatAffects(), item.getInvested(), item.getEntertainerBonus());
					stat.setAffectAmount(affectTotal);

					stats.add(stat);
				}
			}
		}
		
		reciever.setAttachment("buffWorkshop", stats);
		
		if (buffer == reciever)
			reciever.setAttachment("inspireDuration", 215);
		
		//PlayerObject rPlayer = (PlayerObject) reciever.getSlottedObject("ghost");

		//long timeStamp = 0;
		//if (reciever.getAttachment("buffWorkshopTimestamp") != null)
			//timeStamp = (long) reciever.getAttachment("buffWorkshopTimestamp");

		core.buffService.addBuffToCreature(reciever, "buildabuff_inspiration", buffer);
		/*if (core.buffService.addBuffToCreature(reciever, "buildabuff_inspiration", buffer) && !rPlayer.getProfession().equals("entertainer_1a")) {
			if (timeStamp == 0 || (System.currentTimeMillis() - timeStamp > 86400000)) {
				float random = ranWorkshop.nextFloat();
				if (random < 0.75f) {

					if(rPlayer.getProfession().contains("trader")) { core.collectionService.addCollection(buffer, "prof_trader"); } 

					else {
						core.collectionService.addCollection(buffer, rPlayer.getProfession());
					}
				}
			} else { buffer.sendSystemMessage("@collection:buffed_too_soon", (byte) 0); }
			reciever.setAttachment("buffWorkshopTimestamp", System.currentTimeMillis());
		}*/
	}
	
	public int getDanceVisualId(String danceName) {
		Performance p = performances.get(danceName);
		
		//if 0 , then it's no dance. need to handle that in the script.
		return ((p == null) ? 0 : p.getDanceVisualId());
	}
	
	public Map<Long,String> getAvailableDances(CreatureObject actor) {
		
		Map<Long,String> dances = new HashMap<Long, String>();
		for (int index : danceMap.keySet()) {
			if (!canDance(actor, danceMap.get(index) )) { continue; }
			dances.put( new Long( danceMap.get(index).getDanceVisualId()  ), danceMap.get(index).getPerformanceName() );
		}
		
		return dances;
		
	}
	
	public boolean isDance(int visualId) {
		return ( danceMap.get(visualId) != null ) ;
	}
	
	public boolean canDance(CreatureObject actor, Performance dance) {
		if (actor.hasAbility(dance.getRequiredDance())) {
			return true;
		}
		return false;
	}
	
	public boolean canDance(CreatureObject actor, int visualId) {
		if (!isDance(visualId)) { return false; }
		return canDance(actor, danceMap.get(visualId));
	}

	public Performance getDance(int visualId) {
		return danceMap.get(visualId);
	}
	
	public Performance getPerformance(String name) {
		return performances.get(name);
	}
	
	public Performance getPerformanceByIndex(int index) {
		return performancesByIndex.get(index);
	}
	
	public Map<String, PerformanceEffect> getPerformanceEffects() {
		return performanceEffects;
	}
	
	public void startPerformance(CreatureObject actor, int performanceId, int performanceCounter, String skillName, boolean isDance) {
		actor.setPerformanceId(performanceId, isDance);
		actor.setPerformanceCounter(performanceCounter);
		actor.setCurrentAnimation(skillName);
		actor.setPerformanceType(isDance);
		
		actor.startPerformance();
	}
	
	public void startPerformanceExperience(final CreatureObject entertainer) {
		final ScheduledFuture<?> experienceTask = scheduler.scheduleAtFixedRate(new Runnable() {
			
			@Override
			public void run() {
			
				Performance p = performancesByIndex.get(entertainer.getPerformanceId());
				if (p == null) {
					entertainer.setFlourishCount(0);
					return;
				}
				
				int floXP = p.getFlourishXpMod();
				int floCount = entertainer.getFlourishCount();
				
				//FIXME: this is not an accurate implementation yet. It needs group bonuses and other things.
				int XP = (int) Math.round( ((floCount > 2) ? 2 : floCount) * floXP * 3.8 );
				
				entertainer.setFlourishCount(0);
				core.playerService.giveExperience(entertainer, XP);
				
				
			}
			
		},10000, 10000, TimeUnit.MILLISECONDS);
		
		entertainer.setEntertainerExperience(experienceTask);
		
	}
	
	public void startSpectating(final CreatureObject spectator, final CreatureObject performer, boolean spectateType) {

		// visual
		if (spectator.getPerformanceWatchee() == performer && spectateType)
			spectator.getPerformanceWatchee().removeAudience(spectator);
		// music
		else if (spectator.getPerformanceListenee() == performer && !spectateType)
			spectator.getPerformanceListenee().removeAudience(spectator);

		spectator.setPerformanceWatchee(performer);
		performer.addAudience(spectator);
		spectator.setMoodAnimation("entertained");

		final ScheduledFuture<?> spectatorTask = scheduler.scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				if (spectator.getPosition().getDistance2D(performer.getWorldPosition()) > (float) 70) {

					if(((performer.getPerformanceType()) ? "dance" : "music").equals("dance")) {
						spectator.setPerformanceWatchee(null);
						spectator.sendSystemMessage("You stop watching " + performer.getCustomName() + " because " + performer.getCustomName()
								+ " is out of range.", (byte) 0);
					}
					else {
						spectator.setPerformanceListenee(null);
						spectator.sendSystemMessage("You stop listening to " + performer.getCustomName() + " because " + performer.getCustomName()
								+ " is out of range.", (byte) 0);
					}
					spectator.setMoodAnimation("neutral");
					performer.removeAudience(spectator);

					if (spectator.getInspirationTick().cancel(true))
						spectator.getSpectatorTask().cancel(true);
				}
			}

		}, 2, 2, TimeUnit.SECONDS);

		spectator.setSpectatorTask(spectatorTask);

		if(((PlayerObject)performer.getSlottedObject("ghost")).getProfession().equals("entertainer_1a")) {
			handleInspirationTicks(spectator, performer);
		}

		if(spectateType)
			spectator.sendSystemMessage("You start watching " + performer.getCustomName() + ".", (byte) 0);
		else
			spectator.sendSystemMessage("You start listening to " + performer.getCustomName() + ".", (byte) 0);

	}
	
	public void performFlourish(final CreatureObject performer, int flourish) {

		if (performer.getFlourishCount() > 0) {
			performer.sendSystemMessage("@performance:wait_flourish_self", (byte) 0);
			return;
		}
		Performance performance = getPerformanceByIndex(performer.getPerformanceId());

		if(performance == null)
			return;

		String anmFlo = "skill_action_" + flourish;

		if (flourish == 9)
			anmFlo = "mistake";
		
		performer.setFlourishCount(1);
		performer.sendSystemMessage("@performance:flourish_perform", (byte) 0);
		performer.doSkillAnimation(anmFlo);

		scheduler.schedule(new Runnable() {

			@Override
			public void run() {
				performer.setFlourishCount(0);
			}

		}, (long) performance.getLoopDuration(), TimeUnit.SECONDS);
	}
	
	public boolean performEffect(final CreatureObject performer, String command, String effect, TangibleObject target) {
		PerformanceEffect pEffect = performanceEffects.get(command.toLowerCase());

		if(pEffect == null)
			return false;

		String performance = (performer.getPerformanceType()) ? "dance" : "music";

		if(performer.isPerformingEffect()) {
			performer.sendSystemMessage("@performance:effect_wait_self", (byte) 0);
			return false;
		}

		if (performer.getPosture() != Posture.SkillAnimating) {
			performer.sendSystemMessage("@performance:effect_not_performing", (byte) 0);
			return false;
		}

		if(performance.equals("dance") && !pEffect.isDance() || performance.equals("music") && !pEffect.isMusic()) {
			performer.sendSystemMessage("@performance:effect_not_performing_correct", (byte) 0);
			return false;
		}

		if(performer.getAction() < pEffect.getEffectActionCost()) {
			performer.sendSystemMessage("@performance:effect_too_tired", (byte) 0);
			return false;
		}

		performer.setAction(performer.getAction() - pEffect.getEffectActionCost());

		performer.setPerformingEffect(true);
		
		if(target != null)
			target.playEffectObject(effect, "");
		
		else
			performer.playEffectObject(effect, "");
		
		scheduler.schedule(new Runnable() {

			@Override
			public void run() {
				performer.setPerformingEffect(false);
			}

		}, (long) pEffect.getEffectDuration(), TimeUnit.SECONDS);

		return true;
	}
	
	public void handleInspirationTicks(final CreatureObject spectator, final CreatureObject performer) {
		// http://youtu.be/WqyAde-oC7o?t=11m14s  << Player watching entertainer (Has ring only, no pet, + 15 min ticks)
		// TODO: Camp/Cantina checks for expertise and duration bonus %. Right now only using basic values.
		final ScheduledFuture<?> inspirationTick = scheduler.scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				int time = 0; // current buff duration time (minutes)
				int buffCap = 215; // 5 hours 35 minutes - 2 hours (buff duration increase bonus) << Taken from video, doesn't account for performance bonuses etc.

				if (spectator.getAttachment("inspireDuration") != null)
					time+= (int) spectator.getAttachment("inspireDuration");

				if (performer.getSkillMod("expertise_en_inspire_buff_duration_increase") != null) {
					SkillMod durationMod = performer.getSkillMod("expertise_en_inspire_buff_duration_increase");
					buffCap += durationMod.getBase() + durationMod.getModifier();
				}

				if (time >= buffCap) {
					spectator.setAttachment("inspireDuration", buffCap); // incase someone went over cap
					spectator.getInspirationTick().cancel(true);
				} else {
					int entTick = 10;
					if (performer.getSkillMod("expertise_en_inspire_pulse_duration_increase") != null) {
						SkillMod pulseMod = performer.getSkillMod("expertise_en_inspire_pulse_duration_increase");
						entTick += pulseMod.getBase() + pulseMod.getModifier();
					}

					int duration = (time + entTick); // minutes
					int hMinutes = MathUtilities.secondsToHourMinutes(duration * 60);
					int hours = MathUtilities.secondsToWholeHours(duration * 60);

					spectator.showFlyText("spam", "buff_duration_tick_observer", String.valueOf(hours) + " hours , " + hMinutes + " minutes ", 0, (float) 0.66, new RGB(255, 182, 193), 3, 78);

					spectator.setAttachment("inspireDuration", duration);
					//System.out.println("Inspire Duration: " + spectator.getAttachment("inspireDuration") + " on " + spectator.getCustomName());
				}
			}

		}, 10, 10, TimeUnit.SECONDS);
		spectator.setInspirationTick(inspirationTick);
	}
	
	public void handleCoverCharge(final CreatureObject actor, final CreatureObject performer) {
		final int charge = performer.getCoverCharge();

		if (charge == 0)
			return;

		else {
			SUIWindow notification = core.suiService.createMessageBox(InputBoxType.INPUT_BOX_OK, "Cover Charge", performer.getCustomName() +
					" has a cover charge of " + performer.getCoverCharge() + ". Do you wish to pay it?", actor, performer, (float) 30);
			Vector<String> returnParams = new Vector<String>();

			returnParams.add("btnOk:Text");

			notification.addHandler(0, "", Trigger.TRIGGER_OK, returnParams, new SUICallback(){

				@Override
				public void process(SWGObject owner, int eventType, Vector<String> returnList) {
					if (eventType == 0) {
						if (charge > actor.getCashCredits()) {
							actor.sendSystemMessage("You do not have enough credits to cover the charge.", (byte) 0); // TODO: Find the message in the STF files.
							return;
						} else{
							actor.setCashCredits(actor.getCashCredits() - charge);
							actor.sendSystemMessage("You payed the cover charge of " + charge + " to " + performer.getCustomName(), (byte) 0); // TODO: Find the message in the STF files.
							performer.setCashCredits(performer.getCashCredits() + charge);

							startSpectating(actor, performer, performer.getPerformanceType());

						}
					}
				}
			});
			core.suiService.openSUIWindow(notification);
		}
	}
	
	@Override
	public void shutdown() {

	}

}
