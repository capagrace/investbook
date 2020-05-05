/*
 * Portfolio
 * Copyright (C) 2020  Vitalii Ananev <an-vitek@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.portfolio.portfolio.view.excel;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.portfolio.portfolio.converter.SecurityConverter;
import ru.portfolio.portfolio.entity.SecurityEntity;
import ru.portfolio.portfolio.pojo.*;
import ru.portfolio.portfolio.repository.SecurityRepository;
import ru.portfolio.portfolio.repository.TransactionRepository;
import ru.portfolio.portfolio.view.DerivativeEvents;
import ru.portfolio.portfolio.view.DerivativeEventsFactory;
import ru.portfolio.portfolio.view.Table;
import ru.portfolio.portfolio.view.TableFactory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static ru.portfolio.portfolio.view.excel.DerivativesMarketProfitExcelTableHeader.*;

@Component
@RequiredArgsConstructor
public class DerivativesMarketProfitExcelTableFactory implements TableFactory {
    private static final String TAX_FORMULA = "=IF((" + DERIVATIVE_PROFIT_TOTAL.getCellAddr() + "-" + COMMISSION.getCellAddr() + ")<=0," +
            "0,0.13*(" + DERIVATIVE_PROFIT_TOTAL.getCellAddr() + "-" + COMMISSION.getCellAddr() +"))";
    private static final String PROFIT_FORMULA = "=" + DERIVATIVE_PROFIT_TOTAL.getCellAddr()
            + "-" + COMMISSION.getCellAddr()
            + "-" + FORECAST_TAX.getCellAddr();
    private final TransactionRepository transactionRepository;
    private final SecurityRepository securityRepository;
    private final SecurityConverter securityConverter;
    private final DerivativeEventsFactory derivativeEventsFactory;

    public Table create(Portfolio portfolio) {
        Table profit = new Table();
        for (String isin : getSecuritiesIsin(portfolio)) {
            Optional<SecurityEntity> securityEntity = securityRepository.findByIsin(isin);
            if (securityEntity.isPresent()) {
                Security contract = securityConverter.fromEntity(securityEntity.get());
                DerivativeEvents derivativeEvents = derivativeEventsFactory.getDerivativeEvents(portfolio, contract);

                profit.addEmptyRecord();
                profit.addAll(getContractProfit(contract, derivativeEvents));
            }
        }
        return profit;
    }

    private Collection<String> getSecuritiesIsin(Portfolio portfolio) {
        return transactionRepository.findDistinctDerivativeByPortfolioOrderByTimestampDesc(portfolio);
    }

    private Table getContractProfit(Security contract, DerivativeEvents derivativeEvents) {
        Table contractProfit = new Table();
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalProfit = BigDecimal.ZERO;
        int totalContractCount = 0;
        for (DerivativeEvents.DerivativeDailyEvents dailyEvents : derivativeEvents.getDerivativeDailyEvents()) {
            Table.Record record = new Table.Record();
            contractProfit.add(record);
            boolean isFirstRowOfDay = true;
            for (Map.Entry<Transaction, Map<CashFlowType, TransactionCashFlow>> e :
                    dailyEvents.getDailyTransactions().entrySet()) {
                if (!isFirstRowOfDay) {
                    record = new Table.Record();
                    contractProfit.add(record);
                }
                Transaction transaction = e.getKey();
                Map<CashFlowType, TransactionCashFlow> transactionCashFlows = e.getValue();
                record.put(DATE, transaction.getTimestamp());
                record.put(DIRECTION, (transaction.getCount() > 0) ? "покупка" : "продажа");
                record.put(COUNT, Math.abs(transaction.getCount()));
                record.put(QUOTE, Optional.ofNullable(transactionCashFlows.get(CashFlowType.DERIVATIVE_QUOTE))
                        .map(TransactionCashFlow::getValue)
                        .map(q -> "=" + q + "/" + Math.abs(transaction.getCount()))
                        .orElse(null));
                record.put(AMOUNT, Optional.ofNullable(transactionCashFlows.get(CashFlowType.DERIVATIVE_PRICE))
                        .map(TransactionCashFlow::getValue)
                        .orElse(null));
                BigDecimal commission = Optional.ofNullable(transactionCashFlows.get(CashFlowType.COMMISSION))
                        .map(TransactionCashFlow::getValue)
                        .map(BigDecimal::abs)
                        .orElse(BigDecimal.ZERO);
                totalCommission = totalCommission.add(commission);
                record.put(COMMISSION, commission);
                isFirstRowOfDay = false;
            }
            record.put(DATE, dailyEvents.getDailyProfit().getTimestamp());
            record.put(DERIVATIVE_PROFIT_DAY, dailyEvents.getDailyProfit().getValue());
            totalProfit = dailyEvents.getTotalProfit();
            record.put(DERIVATIVE_PROFIT_TOTAL, totalProfit);
            totalContractCount = dailyEvents.getPosition();
            record.put(POSITION, totalContractCount);
        }
        Table.Record total = new Table.Record();
        total.put(CONTRACT, contract.getIsin());
        total.put(DIRECTION, "Итого");
        total.put(COUNT, totalContractCount);
        total.put(COMMISSION, totalCommission);
        total.put(DERIVATIVE_PROFIT_TOTAL, totalProfit);
        total.put(FORECAST_TAX, TAX_FORMULA);
        total.put(PROFIT, PROFIT_FORMULA);
        contractProfit.addFirst(total);

        return contractProfit;
    }
}
