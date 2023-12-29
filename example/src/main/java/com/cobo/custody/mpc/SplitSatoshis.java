package com.cobo.custody.mpc;

import com.cobo.custody.api.client.CoboApiClientFactory;
import com.cobo.custody.api.client.CoboApiRestClient;
import com.cobo.custody.api.client.CoboMPCApiRestClient;
import com.cobo.custody.api.client.config.Env;
import com.cobo.custody.api.client.domain.ApiResponse;
import com.cobo.custody.api.client.domain.account.CoinInfo;
import com.cobo.custody.api.client.domain.transaction.MPCPostTransaction;
import com.cobo.custody.api.client.domain.transaction.MPCTransactionInfos;
import com.cobo.custody.api.client.impl.LocalSigner;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;

public class SplitSatoshis {
    private String apiSecret = "";

    private CoboApiRestClient client;

    private String MPCAPISecret = "";
    private CoboMPCApiRestClient mpcClient;
    private Env TestEnv = Env.DEV;

    public SplitSatoshis() throws Exception {
        apiSecret = System.getProperty("ApiSecret");
        client = CoboApiClientFactory.newInstance(
                new LocalSigner(apiSecret),
                TestEnv,
                true).newRestClient();
        MPCAPISecret = System.getProperty("MPCApiSecret");
        mpcClient = CoboApiClientFactory.newInstance(
                new LocalSigner(MPCAPISecret),
                TestEnv,
                false).newMPCRestClient();
    }

    public static void main(String[] args) throws Exception {
        SplitSatoshis splitSatoshis = new SplitSatoshis();
        String coin = "BTC";
        String txHash = "xxxx";
        Integer voutN = 0;
        // 提供相应的地址，尽量多
        ArrayList<String> addresses = new ArrayList<>();

        ApiResponse<CoinInfo> coinInfo = splitSatoshis.client.getCoinInfo(coin, null);
        if (!coinInfo.isSuccess()) {
            return;
        }

        // 获取txHash  对应的toAddress
        ApiResponse<MPCTransactionInfos> transactions = splitSatoshis.mpcClient.transactionsByTxhash(txHash, null);
        if (!transactions.isSuccess()) {
            return;
        }
        String toAddress = transactions.getResult().getTransactions().get(0).getToAddress();

        // 获取稀有聪
        ApiResponse<GetSatoshisDetails>  rareSatoshiList = splitSatoshis.mpcClient.getRareSatoshis(coin, txHash, voutN);
        if (!rareSatoshiList.isSuccess()) {
            return;
        }

        // 判断有无稀有聪
        if (rareSatoshiList.getResult().getSatoshis().size() <= 0) {
            return;
        }

        // 拆分稀有聪
        BigInteger lastOffset = new BigInteger("0");
        ArrayList<BigInteger> output_values = new ArrayList<>();
        Boolean includeSatoshi = false;
        BigInteger dustThreshold = new BigInteger(String.valueOf(coinInfo.getResult().getDustThreshold()));
        for (GetSatoshisDetail detail : rareSatoshiList.getResult().getSatoshis()) {
            BigInteger delta = new BigInteger(detail.getOffset()).subtract(lastOffset);
            if (delta.compareTo(new BigInteger(String.valueOf(dustThreshold))) < 0) {
                includeSatoshi = true;
                continue;
            }

            if (!includeSatoshi) {
                output_values.add(delta);
            } else {
                output_values.add(dustThreshold);
                output_values.add(delta.subtract(dustThreshold));
            }

            includeSatoshi = false;
            lastOffset = lastOffset.add(delta);

        }

        HashMap<String, BigInteger> toAddressDetails = new HashMap<>();
        for(Integer index = 0; index < output_values.size(); index++) {
            toAddressDetails.put(addresses.get(index), output_values.get(index));
        }

        // 转账
        String requestId = String.valueOf(System.currentTimeMillis());
        ApiResponse<MPCPostTransaction> transferFeeResponse = splitSatoshis.mpcClient.createTransaction(coin, requestId, null, toAddress, toAddressDetails.toString(),
                    null, null, null, null, null, null, null, null, null, null);
        if (!transferFeeResponse.isSuccess()) {
                return ;
        }
        // 当拆分稀有聪的交易上链后，将拆分出稀有聪的utxo，调用lock_spendable api锁定
        // 根据回调获取稀有聪交易。并拿到相应的txHash, voutN
        String satoshisTxHash = "";
        Integer satoshisVoutN = 0;
        splitSatoshis.mpcClient.lockSpendable(coin, satoshisTxHash, voutN);
    }
}