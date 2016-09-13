/* ==========================================
 * jORLib : Java Operations Research Library
 * ==========================================
 *
 * Project Info:  http://www.coin-or.org/projects/jORLib.xml
 * Project Creator:  Joris Kinable (https://github.com/jkinable)
 *
 * (C) Copyright 2015-2016, by Joris Kinable and Contributors.
 *
 * This program and the accompanying materials are licensed under LGPLv2.1
 * as published by the Free Software Foundation.
 */
package org.jorlib.frameworks.columngeneration.pricing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jorlib.frameworks.columngeneration.colgenmain.AbstractColumn;
import org.jorlib.frameworks.columngeneration.io.TimeLimitExceededException;
import org.jorlib.frameworks.columngeneration.util.Configuration;

/**
 * Class which takes care of the parallel execution of the algorithms for the pricing problems.
 * 
 * @author Joris Kinable
 * @version 13-4-2015
 *
 * @param <T> type of model data
 * @param <U> type of column
 * @param <V> type of pricing problem
 *
 */
public class PricingProblemManager<T, U extends AbstractColumn<T, V>,
    V extends AbstractPricingProblem<T>>
{

    /** Configuration file **/
    private static final Configuration config = Configuration.getConfiguration();

    /**
     * For each AbstractPricingProblemSolver and for each Pricing Problem we have a new instance of
     * the AbstractPricingProblemSolver. All instances corresponding to the same solver are bundled
     * together in a PricingProblemBundle. Note that the list of pricingProblemBunddles is
     * hierarchical. The solvers are invoked in the order determined by the order of the bundles.
     */
    private final Map<Class<? extends AbstractPricingProblemSolver<T, U, V>>,
        PricingProblemBundle<T, U, V>> pricingProblemBundles;

    /**
     * Map of tasks which can be invoked in parallel to calculate bounds on the pricing problems
     * Every solver instance is mapped to a callable function which executes
     * {@link AbstractPricingProblemSolver<T,U,V>.#getUpperBound() getUpperBound} on the solver.
     */
    private final Map<AbstractPricingProblemSolver<T, U, V>, Callable<Double>> ppBoundTasks;

    /** Executors **/
    private final ExecutorService executor;
    /** Futures **/
    private final List<Future<Void>> futures;

    /**
     * Creates a new pricing problem manager
     * 
     * @param pricingProblems List of pricing problems
     * @param pricingProblemBundles List of PricingProblemBundles
     */
    public PricingProblemManager(
        List<V> pricingProblems, Map<Class<? extends AbstractPricingProblemSolver<T, U, V>>,
            PricingProblemBundle<T, U, V>> pricingProblemBundles)
    {
        this.pricingProblemBundles = pricingProblemBundles;

        // Create tasks which calculate bounds on the pricing problems
        ppBoundTasks = new HashMap<>();
        for (PricingProblemBundle<T, U, V> pricingProblemBunddle : pricingProblemBundles.values()) {
            for (AbstractPricingProblemSolver<T, U,
                V> solver : pricingProblemBunddle.solverInstances)
            {
                Callable<Double> task = new Callable<Double>()
                {
                    @Override
                    public Double call()
                        throws Exception
                    {
                        return solver.getBound(); // Gets the upper bound on the pricing problem
                                                  // through the solver instance
                    }
                };
                ppBoundTasks.put(solver, task);
            }
        }

        // Define workers
        executor = Executors.newFixedThreadPool(config.MAXTHREADS); // Creates a threat pool
                                                                    // consisting of MAXTHREADS
                                                                    // threats
        futures = new ArrayList<>(pricingProblems.size());
    }

    /**
     * Solve the pricing problems in parallel
     * 
     * @param solver the solver which should be used to solve the pricing problem(s)
     * @return List of columns which have been generated by the solvers. The list is aggregated over
     *         each pricing problem..
     * @throws TimeLimitExceededException exception thrown when timelimit is exceeded.
     */
    public List<U> solvePricingProblems(
        Class<? extends AbstractPricingProblemSolver<T, U, V>> solver)
        throws TimeLimitExceededException
    {
        PricingProblemBundle<T, U, V> bundle = pricingProblemBundles.get(solver);

        // 1. schedule pricing problems
        for (AbstractPricingProblemSolver<T, U, V> solverInstance : bundle.solverInstances) {
            Future<Void> f = executor.submit(solverInstance);
            futures.add(f);
        }

        // 2. Wait for completion and check whether any of the threads has thrown an exception which
        // needs to be handled upstream
        for (Future<Void> f : futures) {
            try {
                f.get(); // get() is a blocking procedure
            } catch (ExecutionException e) {
                if (e.getCause() instanceof TimeLimitExceededException) {
                    this.shutdownAndAwaitTermination(executor); // Shut down the executor.
                    throw (TimeLimitExceededException) e.getCause(); // Propagate the exception
                } else
                    e.printStackTrace();

            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // 3. Collect and return results
        List<U> newColumns = new ArrayList<>();
        for (AbstractPricingProblemSolver<T, U, V> solverInstance : bundle.solverInstances) {
            newColumns.addAll(solverInstance.getColumns());
        }

        return newColumns;
    }

    /**
     * Invokes {@link AbstractPricingProblemSolver#getBound()} getUpperBound} in parallel for all
     * pricing problems defined.
     * 
     * @param solver the solver on which {@link AbstractPricingProblemSolver#getBound()}
     *        getUpperBound} is invoked.
     * @return array containing the bounds calculated for each pricing problem
     */
    public double[] getBoundsOnPricingProblems(
        Class<? extends AbstractPricingProblemSolver<T, U, V>> solver)
    {
        // Get the bunddle of solverInstances corresponding to the solverID
        PricingProblemBundle<T, U, V> bunddle = pricingProblemBundles.get(solver);
        double[] bounds = new double[bunddle.solverInstances.size()];
        // Submit all the relevant getUpperBound() tasks to the executor
        List<Future<Double>> futureList = new ArrayList<>();
        for (AbstractPricingProblemSolver<T, U, V> solverInstance : bunddle.solverInstances) {
            Callable<Double> task = ppBoundTasks.get(solverInstance);
            Future<Double> f = executor.submit(task);
            futureList.add(f);
        }
        // Query the results of each task one by one
        for (int i = 0; i < bounds.length; i++) {
            try {
                bounds[i] = futureList.get(i).get(); // Get result, note that this is a blocking
                                                     // procedure!
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        return bounds;
    }

    /**
     * Future point in time when the pricing problem must be finished
     * 
     * @param timeLimit set time limit for each solver (future point in time).
     */
    public void setTimeLimit(long timeLimit)
    {
        for (PricingProblemBundle<T, U, V> bunddle : pricingProblemBundles.values()) {
            for (AbstractPricingProblemSolver<T, U, V> solverInstance : bunddle.solverInstances) {
                solverInstance.setTimeLimit(timeLimit);
            }
        }
    }

    /**
     * Shut down the executors
     */
    private void shutdownAndAwaitTermination(ExecutorService pool)
    {
        // Prevent new tasks from being submitted
        pool.shutdownNow();
        try {
            // Wait a while for tasks to respond to being cancelled
            if (!pool.awaitTermination(60, TimeUnit.SECONDS))
                System.err.println("Pool did not terminate");
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt nodeStatus
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Close the pricing problems
     */
    public void close()
    {
        executor.shutdownNow();
        // Close pricing problems
        for (PricingProblemBundle<T, U, V> bunddle : pricingProblemBundles.values()) {
            for (AbstractPricingProblemSolver<T, U, V> solverInstance : bunddle.solverInstances) {
                solverInstance.close();
            }
        }
    }

}
