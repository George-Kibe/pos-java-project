const MONEY = new Intl.NumberFormat("en-KE", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const QUANTITY = new Intl.NumberFormat("en-KE", { maximumFractionDigits: 3 });

/** Display only: figures arrive computed by the services. */
export const formatMoney = (value: number) => MONEY.format(value);
export const formatQuantity = (value: number) => QUANTITY.format(value);
export const formatWhen = (value: string | null) => (value ? new Date(value).toLocaleString("en-GB") : "-");
