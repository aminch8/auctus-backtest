package com.auctus.core.simulator;

import com.auctus.core.barseriesprovider.AbstractBarSeriesProvider;
import com.auctus.core.barseriesprovider.BarSeriesProvider;
import com.auctus.core.domains.*;
import com.auctus.core.domains.enums.OrderType;
import com.auctus.core.domains.enums.PeriodicCostInterval;
import com.auctus.core.utils.NumUtil;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Trade;
import org.ta4j.core.num.Num;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
public class Simulator<T extends AbstractTradingSystem> {

    private T tradingSystem;
    private Slippage slippage = Slippage.ofPercentPrice(0);
    private Commission commission = Commission.ofPercentPrice(0);
    private FundingRate fundingRate = FundingRate.ofPercentPrice(0, PeriodicCostInterval.EIGHT_HOURS);
    private List<TradeLog> tradeHistory = new ArrayList<>();

    public Simulator(T tradingSystem) {
        this.tradingSystem = tradingSystem;
    }


    private void simulateTick() {
        AbstractBarSeriesProvider barSeriesProvider = tradingSystem.getBarSeriesProvider();
        BarSeries barSeries = barSeriesProvider.getBaseBarSeries();
        Bar lastBar = barSeries.getLastBar();

        Position runningPosition = tradingSystem.getRunningPosition();

        processTick(lastBar);
        List<Order> activeOrders = tradingSystem.getActiveOrders();


        if (runningPosition.isLong()) {
            Order exitBuy = tradingSystem.onExitBuyCondition();
            if (exitBuy.isReduceOnly() && !exitBuy.getVolume().isZero() && exitBuy.getOrderType() == OrderType.LIMIT)
                tradingSystem.addOrder(exitBuy);
            else analyzeOrder(exitBuy);
        }

        if (runningPosition.isShort()) {
            Order exitSell = tradingSystem.onExitSellCondition();
            if (exitSell.isReduceOnly() && !exitSell.getVolume().isZero() && exitSell.getOrderType() == OrderType.LIMIT)
                tradingSystem.addOrder(exitSell);
            else analyzeOrder(exitSell);
        }

        Order buyOrder = tradingSystem.onBuyCondition();
        if (!buyOrder.getVolume().isZero() && buyOrder.getOrderType() == OrderType.LIMIT)
            tradingSystem.addOrder(buyOrder);
        else analyzeOrder(buyOrder);


        Order sellOrder = tradingSystem.onSellCondition();
        if (!sellOrder.getVolume().isZero() && sellOrder.getOrderType() == OrderType.LIMIT)
            tradingSystem.addOrder(sellOrder);
        else analyzeOrder(sellOrder);


    }


    private void analyzeOrder(Order order) {
        if (order == null || order.getVolume().isZero()) return;

        if (order.isReduceOnly()) {
            //we are looking to close position
           closePositionOrder(order);

        } else {
            // we are looking to open position

            openPositionOrder(order);

        }

    }

    private void analyzeOrders(List<Order> orders) {
        for (Order order : orders) {
            this.analyzeOrder(order);
        }
    }

    private void processTick(Bar bar) {
        Position position = tradingSystem.getRunningPosition();
        List<Order> activeOrders = tradingSystem.getActiveOrders();
        if (position.getSize().isZero()) return;
        Num positionSize = position.getSize();


        if (positionSize.isPositive()) {

            // we are net long

        } else {

            // we are net short

        }

    }

    private void openPositionOrder(Order order){
        Num orderVolume = order.getVolume();
        Num orderPrice = order.getPrice();
        Bar lastCandle = this.tradingSystem.getBarSeriesProvider().getBaseBarSeries().getLastBar();
        Num executedPrice = null;
        Position position = tradingSystem.getRunningPosition();
        if (order.getOrderType() == OrderType.MARKET) {
            executedPrice = lastCandle.getClosePrice();
        }

        if (order.getOrderType() == OrderType.LIMIT) {
            executedPrice = orderPrice;
            if (orderVolume.isPositive()) {
                if (lastCandle.getLowPrice().isGreaterThan(orderPrice)) return;
            } else {
                if (lastCandle.getHighPrice().isLessThan(orderPrice)) return;
            }
        }

        TradeLog tradeLog = TradeLog.createLog(tradingSystem.getSymbol(), orderVolume, executedPrice, lastCandle.getEndTime());
        tradeHistory.add(tradeLog);

        position.setSize(position.getSize().plus(orderVolume));
        tradingSystem.updatePosition(position);
    }

    private void closePositionOrder(Order order){
        Num orderVolume = order.getVolume();
        Bar lastCandle = this.tradingSystem.getBarSeriesProvider().getBaseBarSeries().getLastBar();
        Position position = this.tradingSystem.getRunningPosition();
        Num executedPrice = null;
        Num newNetSize = null;

        switch (order.getOrderType()){
            case LIMIT:
                Num orderPrice = order.getPrice();
                executedPrice = orderPrice;
                if (orderVolume.isPositive()) {
                    //closing short position
                    if (lastCandle.getLowPrice().isGreaterThan(orderPrice)) return;
                    if (position.getSize().isNegative()) {
                        newNetSize = position.getSize().plus(orderVolume).min(NumUtil.getNum(0));
                    } else {
                        log.error("Something has gone wrong... Reduce only order on wrong side of the position?");
                    }
                } else {
                    //closing long position
                    if (lastCandle.getHighPrice().isLessThan(orderPrice)) return;
                    if (position.getSize().isPositive()) {
                        newNetSize = position.getSize().plus(orderVolume).max(NumUtil.getNum(0));
                    } else {
                        log.error("Something has gone wrong... Reduce only order on wrong side of the position?");
                    }
                }
                break;
            case MARKET:
                executedPrice = lastCandle.getClosePrice();
                if (orderVolume.isPositive()) {
                    //closing short position
                    if (position.getSize().isNegative()) {
                        newNetSize = position.getSize().plus(orderVolume).min(NumUtil.getNum(0));
                    } else {
                        log.error("Something has gone wrong... Reduce only order on wrong side of the position?");
                    }
                } else {
                    //closing long position
                    if (position.getSize().isPositive()) {
                        newNetSize = position.getSize().plus(orderVolume).max(NumUtil.getNum(0));
                    } else {
                        log.error("Something has gone wrong... Reduce only order on wrong side of the position?");
                    }
                }
                break;
            default: return;
        }

        Num deltaPositionSize = newNetSize.minus(position.getSize());

        Num realizedProfitAndLoss = deltaPositionSize.multipliedBy(executedPrice);
        tradingSystem.addBalance(realizedProfitAndLoss);

        TradeLog tradeLog = TradeLog.createLog(tradingSystem.getSymbol(), deltaPositionSize, executedPrice, lastCandle.getEndTime());
        tradeHistory.add(tradeLog);

        position.setSize(newNetSize);
        tradingSystem.updatePosition(position);

    }

}
