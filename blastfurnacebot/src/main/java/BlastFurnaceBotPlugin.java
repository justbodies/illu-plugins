import com.google.inject.Provides;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.*;
import net.runelite.api.util.Text;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.iutils.BankUtils;
import net.runelite.client.plugins.iutils.CalculationUtils;
import net.runelite.client.plugins.iutils.InventoryUtils;
import net.runelite.client.plugins.iutils.KeyboardUtils;
import net.runelite.client.plugins.iutils.MouseUtils;
import net.runelite.client.plugins.iutils.NPCUtils;
import net.runelite.client.plugins.iutils.ObjectUtils;
import net.runelite.client.plugins.iutils.PlayerUtils;
import net.runelite.client.plugins.iutils.game.Game;
import net.runelite.client.plugins.iutils.iUtils;
import net.runelite.client.plugins.iutils.scripts.ReflectBreakHandler;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Extension
@PluginDescriptor(name = "Blast furnace", description = "Blast furnace")
@PluginDependency(iUtils.class)
public class BlastFurnaceBotPlugin extends Plugin {
    private static final Logger log = LoggerFactory.getLogger(BlastFurnaceBotPlugin.class);

    private static final int BAR_DISPENSER = 9092;

    private static final int BF_COFFER = 29330;

    private static final long COST_PER_HOUR = 72000L;

    private static final String FOREMAN_PERMISSION_TEXT = "Okay, you can use the furnace for ten minutes. Remember, you only need half as much coal as with a regular furnace.";

    List<Integer> inventorySetup = new ArrayList<>();

    private GameObject conveyorBelt;

    private GameObject barDispenser;

    private ForemanTimer foremanTimer;

    @Inject
    private Client client;

    @Inject
    KeyboardUtils keyboard;

    @Inject
    private ItemManager itemManager;

    @Inject
    BankUtils bankUtils;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private BotOverlay overlay;
    @Inject
    public ReflectBreakHandler chinBreakHandler;

    @Inject
    Game game;
    @Inject
    private ProfitOverlay profitOverlay;

    @Inject
    private InfoBoxManager infoBoxManager;

    @Inject
    private BlastFurnaceBotConfig config;

    @Inject
    InventoryUtils inventory;

    @Inject
    ExecutorService executorService;

    @Inject
    ObjectUtils object;

    @Inject
    PlayerUtils player;

    @Inject
    MouseUtils mouse;

    @Inject
    NPCUtils npc;

    @Inject
    BankUtils bank;

    @Inject
    CalculationUtils calc;

    @Inject
    private iUtils utils;

    BlastFurnaceState state;

    MenuEntry targetMenu;

    GameObject getConveyorBelt() {
        return this.conveyorBelt;
    }

    GameObject getBarDispenser() {
        return this.barDispenser;
    }

    LocalPoint beforeLoc = new LocalPoint(0, 0);

    Instant botTimer;

    Bars bar;

    int cofferRefill;

    int cofferMinValue;

    int tickDelay;

    int barPrice;

    int orePrice;

    int coalPrice;

    int staminaPotPrice;

    int timeout = 0;

    public static long sleepLength;
    public static int tickLength;
    public static boolean startBot;

    int previousAmount = 0;

    long barsPerHour = 0L;

    long barsAmount = 0L;

    long profit = 0L;


    private boolean coalBagFull;

    protected void startUp() {
        chinBreakHandler.registerPlugin(this);
        //getItemPrices();
    }

    protected void shutDown() {
        chinBreakHandler.unregisterPlugin(this);
        resetVals();
    }

    @Subscribe
    private void onConfigChange(ConfigChanged event) {
        if (event.getGroup().equals("blastfurnacebot"))
            switch (event.getKey()) {
                case "cofferThreshold":
                    this.cofferMinValue = this.config.cofferThreshold();
                    log.info("Minimum coffer value updated to: " + this.cofferMinValue);
                    break;
                case "cofferAmount":
                    this.cofferRefill = this.config.cofferAmount();
                    log.info("Coffer refill value updated to: " + this.cofferRefill);
                    break;
                case "delayAmount":
                    this.tickDelay = this.config.delayAmount();
                    log.info("tick delay value updated to: " + this.tickDelay);
                    break;
                case "bar":
                    this.bar = this.config.getBar();
                    //getItemPrices();
                    log.info(this.config.getBar().name());
                    log.info("Bar configured to: " + this.bar.name());
                    this.barsAmount = 0L;
                    this.previousAmount = 0;
                    this.barsPerHour = 0L;
                    this.profit = 0L;
                    initInventory();
                    this.botTimer = Instant.now();
                    break;
            }
    }
    public long sleepDelay() {
        sleepLength = calc.randomDelay(config.sleepWeightedDistribution(), config.sleepMin(), config.sleepMax(), config.sleepDeviation(), config.sleepTarget());
        return sleepLength;
    }

    public int tickDelay() {
        tickLength = (int) calc.randomDelay(config.tickDelayWeightedDistribution(), config.tickDelayMin(), config.tickDelayMax(), config.tickDelayDeviation(), config.tickDelayTarget());
        return tickLength;
    }
    public void resetVals() {
        overlayManager.remove(overlay);
        overlayManager.remove(profitOverlay);
        startBot = false;
        chinBreakHandler.unregisterPlugin(this);
        this.conveyorBelt = null;
        this.barDispenser = null;
        this.foremanTimer = null;
        this.botTimer = null;
        this.barsAmount = 0L;
        this.previousAmount = 0;
        this.barsPerHour = 0L;
        this.profit = 0L;
    }
    @Subscribe
    private void onConfigButtonPressed(ConfigButtonClicked configButtonClicked) {
        if (!configButtonClicked.getGroup().equalsIgnoreCase("blastfurnacebot")) {
            return;
        }
        log.debug("button {} pressed!", configButtonClicked.getKey());
        if (configButtonClicked.getKey().equals("startButton")) {
            if (!startBot) {
                Player player = client.getLocalPlayer();
                if (client != null && player != null && client.getGameState() == GameState.LOGGED_IN) {
                    log.info("Starting Blast furnace");
                    overlayManager.add((Overlay)this.overlay);
                    overlayManager.add((Overlay)this.profitOverlay);
                    coalBagFull = false;
                    timeout = 0;
                    targetMenu = null;
                    botTimer = Instant.now();
                    bar = this.config.getBar();
                    initInventory();
                    cofferMinValue = this.config.cofferThreshold();
                    cofferRefill = this.config.cofferAmount();
                    tickDelay = this.config.delayAmount();
                    overlayManager.add(overlay);
                    beforeLoc = client.getLocalPlayer().getLocalLocation();
                    startBot = true;
                } else {
                    log.info("Start logged in");
                }
            } else {
                resetVals();
            }
        }
    }
    private void initInventory() {
        this

                .inventorySetup = (this.bar.getMinCoalAmount() == 0) ? List.of(Integer.valueOf(12631), Integer.valueOf(12629), Integer.valueOf(12627), Integer.valueOf(12625)) : List.of(Integer.valueOf(12019), Integer.valueOf(12631), Integer.valueOf(12629), Integer.valueOf(12627), Integer.valueOf(12625));
        log.info("required inventory items: {}", this.inventorySetup.toString());
    }

    private void getItemPrices() {
        this.barPrice = this.utils.getOSBItem(this.bar.getItemID()).getSell_average();
        this.orePrice = this.utils.getOSBItem(this.bar.getOreID()).getBuy_average();
        this.coalPrice = this.utils.getOSBItem(Ores.COAL.getOreID()).getBuy_average();
        this.staminaPotPrice = this.utils.getOSBItem(12625).getBuy_average();
        log.info("{} price: {}, Ore price: {}, Coal price: {}, stamina pot price: {}", new Object[] { this.bar.name(), Integer.valueOf(this.barPrice), Integer.valueOf(this.orePrice), Integer.valueOf(this.coalPrice), Integer.valueOf(this.staminaPotPrice) });
    }

    public void barsMade() {
        int amount = this.client.getVar(this.bar.getVarbit());
        if (amount != this.previousAmount) {
            this.previousAmount = amount;
            this.barsAmount += amount;
        }
    }

    public long profitPerHour() {
        int foremanMultiplier = (this.client.getRealSkillLevel(Skill.SMITHING) < 60) ? 1 : 0;
        switch (this.bar.name()) {
            case "IRON_BAR":
            case "SILVER_BAR":
            case "GOLD_BAR":
                return this.barsPerHour * this.barPrice - this.barsPerHour * this.orePrice + (9 * this.staminaPotPrice) + 72000L + (foremanMultiplier * 60000);
            case "STEEL_BAR":
                return this.barsPerHour * this.barPrice - this.barsPerHour * this.orePrice + this.barsPerHour * this.coalPrice + (9 * this.staminaPotPrice) + 72000L + (foremanMultiplier * 60000);
            case "MITHRIL_BAR":
                return this.barsPerHour * this.barPrice - this.barsPerHour * this.orePrice + this.barsPerHour * 2L * this.coalPrice + (9 * this.staminaPotPrice) + 72000L + (foremanMultiplier * 60000);
            case "ADAMANTITE_BAR":
                return this.barsPerHour * this.barPrice - this.barsPerHour * this.orePrice + this.barsPerHour * 3L * this.coalPrice + (9 * this.staminaPotPrice) + 72000L;
            case "RUNITE_BAR":
                return this.barsPerHour * this.barPrice - this.barsPerHour * this.orePrice + this.barsPerHour * 4L * this.coalPrice + (9 * this.staminaPotPrice) + 72000L;
        }
        return 0L;
    }

    public long getBarsPH() {
        Duration duration = Duration.between(this.botTimer, Instant.now());
        return this.barsAmount * 3600000L / duration.toMillis();
    }

    private void updateCalc() {
        barsMade();
        this.barsPerHour = getBarsPH();
        this.profit = profitPerHour();
    }

    private void openBank() {
        GameObject bankObject = this.object.findNearestGameObject(new int[] { 26707 });
        if (bankObject != null) {
            this.targetMenu = new MenuEntry("", "", bankObject.getId(), MenuAction.GAME_OBJECT_FIRST_OPTION.getId(), bankObject.getSceneMinLocation().getX(), bankObject.getSceneMinLocation().getY(), true);
            this.utils.doActionMsTime(this.targetMenu, new Point(0, 0), sleepDelay());
            this.timeout = tickDelay();
        }
    }

    private void putConveyorBelt() {
        Widget dialogue = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTION1);
        if(dialogue != null && !dialogue.isHidden()) {
            this.targetMenu = new MenuEntry("", "", 0, 30, 1, 14352385, false);
            this.utils.doActionMsTime(this.targetMenu, new Point(0, 0), sleepDelay());
            executorService.submit(()->game.waitUntil(() -> dialogue.isHidden()));


        } else {
            this.targetMenu = new MenuEntry("", "", this.conveyorBelt.getId(), MenuAction.GAME_OBJECT_FIRST_OPTION.getId(), this.conveyorBelt.getSceneMinLocation().getX(), this.conveyorBelt.getSceneMinLocation().getY(), false);
            this.utils.doActionMsTime(this.targetMenu, new Point(0, 0), sleepDelay());
            this.timeout = tickDelay();
        }
        }

    private void collectFurnace() {
        log.info("At collectFurnace(), collecting bars");
        Widget collectDialog = this.client.getWidget(WidgetInfo.MULTI_SKILL_MENU);
        if(collectDialog != null && !collectDialog.isHidden()) {
            log.info("Collect dialog");
            this.targetMenu = new MenuEntry("", "", 1, 57, -1, 17694734, false);
            this.utils.doActionMsTime(this.targetMenu, new Point(0, 0), sleepDelay());
        } else {
            this.targetMenu = (this.client.getVar(Varbits.BAR_DISPENSER) == 1) ? new MenuEntry("", "", 0, MenuAction.WALK.getId(), this.barDispenser.getSceneMinLocation().getX(), this.barDispenser.getSceneMinLocation().getY(), false) : new MenuEntry("", "", this.barDispenser.getId(), MenuAction.GAME_OBJECT_FIRST_OPTION.getId(), this.barDispenser.getSceneMinLocation().getX(), this.barDispenser.getSceneMinLocation().getY(), false);
            this.utils.doActionMsTime(this.targetMenu, new Point(0, 0), sleepDelay());
            this.timeout = tickDelay();
        }
    }

    private void fillCoalBag(WidgetItem coalBag) {
        this.targetMenu = new MenuEntry("", "", coalBag.getId(), MenuAction.ITEM_FIRST_OPTION.getId(), coalBag.getIndex(), 9764864, false);
        this.utils.doActionMsTime(this.targetMenu, new Point(0, 0), sleepDelay());
        timeout = tickDelay();
    }

    private void emptyCoalBag(WidgetItem coalBag) {
        this.targetMenu = new MenuEntry("", "", coalBag.getId(), MenuAction.ITEM_FOURTH_OPTION.getId(), coalBag.getIndex(), 9764864, false);
        this.utils.doActionMsTime(this.targetMenu, new Point(0, 0), sleepDelay());
        timeout = tickDelay();
    }

    private BlastFurnaceState collectBars() {
        if (this.inventory.getEmptySlots() < 26) {
            log.info("collect bars but need inventory space first");
            openBank();
            return BlastFurnaceState.OPENING_BANK;
        }
        collectFurnace();
        return BlastFurnaceState.COLLECTING_BARS;
    }

    private boolean shouldCheckForemanFee() {
        return (this.client.getRealSkillLevel(Skill.SMITHING) < 60 && (this.foremanTimer == null ||
                Duration.between(Instant.now(), this.foremanTimer.getEndTime()).toSeconds() <= 30L));
    }

    private void setForemanTime(Widget npcDialog) {
        String npcText = Text.sanitizeMultilineText(npcDialog.getText());
        if (npcText.equals("Okay, you can use the furnace for ten minutes. Remember, you only need half as much coal as with a regular furnace."))
            this.foremanTimer = new ForemanTimer(this, this.itemManager);
    }

    private BlastFurnaceState getState() {
        if (this.conveyorBelt == null || this.barDispenser == null) {
            this.conveyorBelt = this.object.findNearestGameObject(new int[] { 9100 });
            this.barDispenser = this.object.findNearestGameObject(new int[] { 9092 });
            if (this.conveyorBelt == null || this.barDispenser == null)
                return BlastFurnaceState.OUT_OF_AREA;
        }
        if (this.state == BlastFurnaceState.OUT_OF_ITEMS) {
            this.utils.sendGameMessage("Out of of materials, log off!!!");
            return BlastFurnaceState.OUT_OF_AREA;
        }
        if (this.timeout > 0) {

            return BlastFurnaceState.TIMEOUT;
        }
        if (this.player.isMoving()) {
            this.player.handleRun(30, 60);
            this.timeout = tickDelay();
            return BlastFurnaceState.MOVING;
        }
        if (!this.bank.isOpen()) {
            if (shouldCheckForemanFee()) {
                if (!this.inventory.containsItem(995) && !this.inventory.containsItem(2500)) {
                    openBank();
                    return BlastFurnaceState.OPENING_BANK;
                }
                Widget payDialog = this.client.getWidget(WidgetInfo.DIALOG_OPTION_OPTION1);
                if (payDialog != null) {
                    this.targetMenu = new MenuEntry("", "", 0, MenuAction.WIDGET_TYPE_6.getId(), 1, 14352385, false);
                    this.utils.doActionMsTime(this.targetMenu, new Point(0, 0), sleepDelay());
                    return BlastFurnaceState.PAY_FOREMAN;
                }
                Widget npcDialog = this.client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
                if (npcDialog != null) {
                    setForemanTime(npcDialog);
                    return BlastFurnaceState.PAY_FOREMAN;
                }
                NPC foreman = this.npc.findNearestNpc(new int[] { 2923 });
                if (foreman != null) {
                    this.targetMenu = new MenuEntry("", "", foreman.getIndex(), MenuAction.NPC_THIRD_OPTION.getId(), 0, 0, false);
                    this.utils.doActionMsTime(this.targetMenu, new Point(0, 0), sleepDelay());
                    return BlastFurnaceState.PAY_FOREMAN;
                }
            }
            if (!this.client.getWidget(162, 41).isHidden()) {
                log.info(client.getWidget(162,41).getText());
                List<Integer> refillList = Arrays.asList(new Integer[] { Integer.valueOf(995), Integer.valueOf(this.cofferRefill) });
                if (!this.inventory.containsItem(refillList)) {
                    openBank();
                    return BlastFurnaceState.OPENING_BANK;
                }
                int randDepositAmount = this.calc.getRandomIntBetweenRange(this.cofferRefill, 70000);
                List<Integer> items = Arrays.asList(new Integer[] { Integer.valueOf(995), Integer.valueOf(randDepositAmount) });
                int depositAmount = this.inventory.containsItem(items) ? randDepositAmount : this.cofferRefill;
                log.info("trying");
                this.executorService.submit(() -> {
                    this.keyboard.typeString(""+depositAmount);
                    this.utils.game.sleepApproximately(50);
                    this.keyboard.pressKey(10);

                });
                return BlastFurnaceState.FILL_COFFER;
            }
            this.player.handleRun(20, 20);
            if (this.inventory.containsItem(this.bar.getItemID())) {
                openBank();
                return BlastFurnaceState.OPENING_BANK;
            }
            if (this.client.getVar(Varbits.BAR_DISPENSER) > 0) {
                if (this.inventory.getEmptySlots() < 26) {
                    openBank();
                    return BlastFurnaceState.OPENING_BANK;
                }
                collectFurnace();
                return BlastFurnaceState.COLLECTING_BARS;
            }
            if (this.client.getVar(Varbits.BLAST_FURNACE_COFFER) < this.cofferMinValue) {
                List<Integer> refillList = Arrays.asList(new Integer[] { Integer.valueOf(995), Integer.valueOf(this.cofferRefill) });
                if (this.inventory.containsItem(refillList)) {
                    GameObject coffer = this.object.findNearestGameObject(new int[] { 29330 });
                    if (coffer != null) {
                        Widget dialogue = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTION1);
                        if(dialogue != null && !dialogue.isHidden()) {
                            Widget[] test = dialogue.getDynamicChildren();
                           if(test.length >= 5) {
                               this.targetMenu = new MenuEntry("", "", 0, 30, 1, 14352385, false);
                               this.utils.doActionMsTime(this.targetMenu, new Point(0, 0), sleepDelay());
                               timeout = tickDelay();
                               return BlastFurnaceState.FILL_COFFER;
                           }

                        }
                        this.targetMenu = new MenuEntry("", "", coffer.getId(), MenuAction.GAME_OBJECT_FIRST_OPTION.getId(), coffer.getSceneMinLocation().getX(), coffer.getSceneMinLocation().getY(), false);
                        this.utils.doActionMsTime(this.targetMenu, new Point(0, 0), sleepDelay());
                        this.timeout = tickDelay();
                    } else {
                        this.utils.sendGameMessage("Coffer is null, wrong world?");
                    }
                    return BlastFurnaceState.FILL_COFFER;
                }
                openBank();
                return BlastFurnaceState.OPENING_BANK;
            }
            GameObject bank = this.object.findNearestGameObject(new int[] { 26707 });
            if (bank != null) {
                WidgetItem coalBag = this.inventory.getWidgetItem(12019);
                if (this.client.getLocalPlayer().getWorldLocation().distanceTo(bank.getWorldLocation()) < 8) {
                    if (this.inventory.getItems(List.of(Integer.valueOf(453), Integer.valueOf(this.bar.getOreID()))).isEmpty()) {
                        openBank();
                        return BlastFurnaceState.OPENING_BANK;
                    }
                    if (coalBag != null) {
                        if (!this.coalBagFull) {
                            if (this.inventory.containsItem(453))
                                fillCoalBag(coalBag);
                            if (this.inventory.containsItem(this.bar.getOreID())) {
                                putConveyorBelt();
                                return BlastFurnaceState.PUT_CONVEYOR_BELT;
                            }
                        }
                        if (this.coalBagFull &&
                                !this.inventory.getItems(List.of(Integer.valueOf(453), Integer.valueOf(this.bar.getOreID()))).isEmpty()) {
                            putConveyorBelt();
                            return BlastFurnaceState.PUT_CONVEYOR_BELT;
                        }
                    }
                } else {
                    if (this.inventory.getItems(List.of(Integer.valueOf(453), Integer.valueOf(this.bar.getOreID()))).isEmpty()) {
                        if (!this.coalBagFull || coalBag == null) {
                            if (this.client.getVar(Varbits.BAR_DISPENSER) > 0) {
                                collectFurnace();
                                return BlastFurnaceState.COLLECTING_BARS;
                            }
                            openBank();
                            return BlastFurnaceState.OPENING_BANK;
                        }
                        if (this.coalBagFull && coalBag != null)
                            emptyCoalBag(coalBag);
                    }
                    if (!this.inventory.getItems(List.of(Integer.valueOf(453), Integer.valueOf(this.bar.getOreID()))).isEmpty()) {
                        putConveyorBelt();
                        if (!this.coalBagFull || coalBag == null) {
                            this.timeout = tickDelay();
                            return BlastFurnaceState.PUT_CONVEYOR_BELT;
                        }
                    }
                }
            }
        } else if (this.bank.isOpen()) {
            WidgetItem inventoryBar = this.inventory.getWidgetItem(this.bar.getItemID());
            if (inventoryBar != null) {
                log.info("depositing bars");
                this.bank.depositAllExcept(this.inventorySetup);
                return BlastFurnaceState.DEPOSITING;
            }
            if (this.client.getVar(Varbits.BAR_DISPENSER) > 0) {
                log.info("bars ready for collection, bank is open, depositing inventory and collecting");
                if (this.inventory.getEmptySlots() < 26) {
                    this.bank.depositAll();
                    return BlastFurnaceState.DEPOSITING;
                }
                return collectBars();
            }
            if (this.client.getVar(Varbits.BLAST_FURNACE_COFFER) < this.cofferMinValue || shouldCheckForemanFee()) {
                List<Integer> refillList = Arrays.asList(new Integer[] { Integer.valueOf(995), Integer.valueOf(this.cofferRefill) });
                if (this.inventory.containsItem(refillList)) {
                    this.bank.close();
                    return BlastFurnaceState.FILL_COFFER;
                }
                if (this.inventory.isFull() && !this.inventory.containsItem(995)) {
                    log.info("Depositing inventory to make room for coins");
                    this.bank.depositAllExcept(this.inventorySetup);
                    return BlastFurnaceState.DEPOSITING;
                }
                if (this.bank.contains(995, this.cofferRefill)) {
                    Widget bankCoins = this.bankUtils.getBankItemWidget(995);
                    this.bank.withdrawAllItem(bankCoins);
                    return BlastFurnaceState.FILL_COFFER;
                }
                this.utils.sendGameMessage("Out of coins, required: " + this.cofferRefill);
                this.bank.close();
                this.utils.sendGameMessage("Log Off.");
                resetVals();
                return BlastFurnaceState.OUT_OF_ITEMS;
            }
            Widget staminaPotionBank = this.bankUtils.getBankItemWidgetAnyOf(new int[] { 12631, 12629, 12627, 12625 });
            if (staminaPotionBank != null && this.inventory.getItems(List.of(Integer.valueOf(12631), Integer.valueOf(12629), Integer.valueOf(12627), Integer.valueOf(12625))).isEmpty()) {
                this.bank.depositAllExcept(this.inventorySetup);
                log.info("withdrawing stam pot");
                this.bank.withdrawItem(staminaPotionBank);
                return BlastFurnaceState.WITHDRAWING;
            }
            if (!this.inventory.containsItem(12019) && this.inventorySetup.contains(Integer.valueOf(12019))) {
                Widget coalBagBank = this.bankUtils.getBankItemWidget(12019);
                if (coalBagBank != null) {
                    this.bank.depositAllExcept(this.inventorySetup);
                    log.info("withdrawing coal bag");
                    this.bank.withdrawItem(coalBagBank);
                    return BlastFurnaceState.WITHDRAWING;
                }
                this.utils.sendGameMessage("We don't have a coal bag!");
                this.bank.close();
                this.utils.sendGameMessage("Log Off.");
                return BlastFurnaceState.OUT_OF_ITEMS;
            }
            if ((this.client.getVar(Ores.COAL.getVarbit()) <= this.bar.getMinCoalAmount() || !this.coalBagFull) && this.client.getVar(Ores.COAL.getVarbit()) < 220 && this.bar.getMinCoalAmount() != 0) {
                if (this.inventory.containsItem(453)) {
                    if (!this.coalBagFull) {
                        this.bank.close();
                        return BlastFurnaceState.FILL_COAL_BAG;
                    }
                    putConveyorBelt();
                    return BlastFurnaceState.PUT_CONVEYOR_BELT;
                }
                if (this.inventory.isFull()) {
                    this.bank.depositAllExcept(this.inventorySetup);
                    this.utils.sendGameMessage("inventory is full but need to withdraw coal");
                    return BlastFurnaceState.OUT_OF_ITEMS;
                }
                Widget coalBank = this.bankUtils.getBankItemWidget(453);
                if (coalBank != null) {
                    this.bank.depositAllExcept(this.inventorySetup);
                    log.info("withdrawing coal");
                    this.bank.withdrawAllItem(coalBank);
                    log.info("sleeping");
                    return BlastFurnaceState.WITHDRAWING;
                }
                this.utils.sendGameMessage("out of coal, log off.");
                return BlastFurnaceState.OUT_OF_ITEMS;
            }
            if (this.client.getVar(Ores.COAL.getVarbit()) > this.bar.getMinCoalAmount() || this.bar.getMinCoalAmount() == 0) {
                if (this.inventory.isFull() || this.inventory.containsItem(this.bar.getOreID())) {
                    if (this.inventory.containsItem(this.bar.getOreID())) {
                        log.info("putting Ore onto belt");
                        putConveyorBelt();
                        return BlastFurnaceState.PUT_CONVEYOR_BELT;
                    }
                    this.bank.depositAllExcept(this.inventorySetup);
                    this.utils.sendGameMessage("need to withdraw Ore but inventory is full, something went wrong.");
                    return BlastFurnaceState.OUT_OF_ITEMS;
                }
                Widget bankOre = this.bankUtils.getBankItemWidget(this.bar.getOreID());
                if (bankOre != null) {
                    this.bank.depositAllExcept(this.inventorySetup);
                    log.info("withdrawing ore");
                    this.bank.withdrawAllItem(bankOre);
                    this.timeout = tickDelay();
                    return BlastFurnaceState.WITHDRAWING;
                }
                this.utils.sendGameMessage("Out of ore, log off");
                return BlastFurnaceState.OUT_OF_ITEMS;
            }
        }
        return null;
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (this.targetMenu == null) {
            log.info("Modified MenuEntry is null");
            return;
        }
        if (this.utils.getRandomEvent()) {
            log.info("Blast furnace bot not overriding click due to random event");
            return;
        }
        if (this.targetMenu.getIdentifier() == 12019) {
            if (this.targetMenu.getOpcode() == MenuAction.ITEM_FIRST_OPTION.getId())
                this.coalBagFull = true;
            if (this.targetMenu.getOpcode() == MenuAction.ITEM_FOURTH_OPTION.getId())
                this.coalBagFull = false;
        }
        //log.info("inserting menu at MOC event: " + this.targetMenu.toString());
        event.setMenuEntry(this.targetMenu);
        this.timeout = tickDelay();
        this.targetMenu = null;
    }

    @Provides
    BlastFurnaceBotConfig provideConfig(ConfigManager configManager) {
        return (BlastFurnaceBotConfig)configManager.getConfig(BlastFurnaceBotConfig.class);
    }

    @Subscribe
    private void onGameObjectSpawned(GameObjectSpawned event) {
        GameObject gameObject = event.getGameObject();
        switch (gameObject.getId()) {
            case 9100:
                this.conveyorBelt = gameObject;
                break;
            case 9092:
                this.barDispenser = gameObject;
                break;
        }
    }

    @Subscribe
    private void onGameObjectDespawned(GameObjectDespawned event) {
        GameObject gameObject = event.getGameObject();
        switch (gameObject.getId()) {
            case 9100:
                this.conveyorBelt = null;
                break;
            case 9092:
                this.barDispenser = null;
                break;
        }
    }

    @Subscribe
    private void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOADING) {
            this.conveyorBelt = null;
            this.barDispenser = null;
        }
    }

    @Subscribe
    private void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() != 270 && event.getGroupId() != 162 && event.getGroupId() != 219)
            return;
        if(!startBot)
            return;

        if (event.getGroupId() == 270) {
//            Widget collectDialog = this.client.getWidget(WidgetInfo.MULTI_SKILL_MENU);
//            if(payDialog != null && !payDialog.isHidden()) {
//                log.info("Belt dialog");
//                this.targetMenu = new MenuEntry("", "", 1, 57, -1, 17694734, false);
//                this.utils.doActionMsTime(this.targetMenu, new Point(0, 0), sleepDelay());
//            }
            return;
        }
        if (event.getGroupId() == 219) {
//               this.targetMenu = new MenuEntry("", "", 0, 30, 1, 14352385, false);
//               this.utils.doActionMsTime(this.targetMenu, new Point(0, 0), sleepDelay());
            return;
        }
    }


    @Subscribe
    private void onGameTick(GameTick event) {
        if(!startBot)
            return;
        if (chinBreakHandler.shouldBreak(this)) {
            chinBreakHandler.startBreak(this);
            timeout = 5;
        }
        if (timeout > 0) {
            timeout--;
            return;
        }

        if (this.client != null && this.client.getLocalPlayer() != null && this.client.getGameState() == GameState.LOGGED_IN) {
            updateCalc();
            if (!iUtils.iterating) {
                this.state = getState();
                this.beforeLoc = this.client.getLocalPlayer().getLocalLocation();
                if (this.state != null) {
                    log.info(this.state.name());
                    switch (this.state) {
                        case TIMEOUT:
                             state = BlastFurnaceState.TIMEOUT;
                            this.timeout--;
                            return;
                    }
                } else {
                    log.info("state is null");
                }
            } else {
                log.info("utils is iterating");
            }
        }
    }
    @Subscribe
    private void onChatMessage(ChatMessage event) {


    }
}
